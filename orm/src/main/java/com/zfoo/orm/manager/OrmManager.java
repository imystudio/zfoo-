/*
 * Copyright (C) 2020 The zfoo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.zfoo.orm.manager;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.*;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.zfoo.orm.OrmContext;
import com.zfoo.orm.cache.EntityCaches;
import com.zfoo.orm.cache.IEntityCaches;
import com.zfoo.orm.model.anno.*;
import com.zfoo.orm.model.config.OrmConfig;
import com.zfoo.orm.model.entity.IEntity;
import com.zfoo.orm.model.vo.EntityDef;
import com.zfoo.orm.model.vo.IndexDef;
import com.zfoo.orm.model.vo.IndexTextDef;
import com.zfoo.protocol.collection.ArrayUtils;
import com.zfoo.protocol.collection.CollectionUtils;
import com.zfoo.protocol.exception.RunException;
import com.zfoo.protocol.util.AssertionUtils;
import com.zfoo.protocol.util.JsonUtils;
import com.zfoo.protocol.util.ReflectionUtils;
import com.zfoo.protocol.util.StringUtils;
import com.zfoo.util.math.RandomUtils;
import com.zfoo.util.net.HostAndPort;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * @author godotg
 * @version 3.0
 */
public class OrmManager implements IOrmManager {

    private OrmConfig ormConfig;

    private MongoClient mongoClient;
    private MongoDatabase mongodbDatabase;

    /**
     * ?????????Entity?????????key????????????class???value????????????Entity????????????????????????????????????????????????
     */
    private final Map<Class<?>, Boolean> allEntityCachesUsableMap = new HashMap<>();

    private final Map<Class<? extends IEntity<?>>, IEntityCaches<?, ?>> entityCachesMap = new HashMap<>();

    private final Map<Class<? extends IEntity<?>>, String> collectionNameMap = new ConcurrentHashMap<>();

    public OrmConfig getOrmConfig() {
        return ormConfig;
    }

    public void setOrmConfig(OrmConfig ormConfig) {
        this.ormConfig = ormConfig;
    }

    @Override
    public void initBefore() {
        var entityDefMap = scanEntityClass();

        for (var entityDef : entityDefMap.values()) {
            var entityCaches = new EntityCaches(entityDef);
            entityCachesMap.put(entityDef.getClazz(), entityCaches);
            allEntityCachesUsableMap.put(entityDef.getClazz(), false);
        }

        CodecRegistry pojoCodecRegistry = CodecRegistries.fromRegistries(
                MongoClientSettings.getDefaultCodecRegistry(),
                CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build()));

        var mongoBuilder = MongoClientSettings
                .builder()
                .codecRegistry(pojoCodecRegistry);

        // ?????????????????????
        var hostConfig = ormConfig.getHost();
        if (CollectionUtils.isNotEmpty(hostConfig.getAddress())) {
            var hostList = HostAndPort.toHostAndPortList(hostConfig.getAddress().values())
                    .stream()
                    .map(it -> new ServerAddress(it.getHost(), it.getPort()))
                    .collect(Collectors.toList());
            mongoBuilder.applyToClusterSettings(builder -> builder.hosts(hostList));
        }

        // ???????????????????????????
        if (StringUtils.isNotBlank(hostConfig.getUser()) && StringUtils.isNotBlank(hostConfig.getPassword())) {
            mongoBuilder.credential(MongoCredential.createCredential(hostConfig.getUser(), "admin", hostConfig.getPassword().toCharArray()));
        }

        // ????????????????????????
        var maxConnection = Runtime.getRuntime().availableProcessors() * 2 + 1;
        mongoBuilder.applyToConnectionPoolSettings(builder -> builder.maxSize(maxConnection).minSize(1));

        mongoClient = MongoClients.create(mongoBuilder.build());
        mongodbDatabase = mongoClient.getDatabase(hostConfig.getDatabase());

        // ????????????
        for (var entityDef : entityDefMap.values()) {
            var indexDefMap = entityDef.getIndexDefMap();
            if (CollectionUtils.isNotEmpty(indexDefMap)) {
                var collection = getCollection(entityDef.getClazz());
                for (var indexDef : indexDefMap.entrySet()) {
                    var fieldName = indexDef.getKey();
                    var index = indexDef.getValue();
                    var hasIndex = false;
                    for (var document : collection.listIndexes()) {
                        var keyDocument = (Document) document.get("key");
                        if (keyDocument.containsKey(fieldName)) {
                            hasIndex = true;
                        }
                    }
                    if (!hasIndex) {
                        var indexOptions = new IndexOptions();
                        indexOptions.unique(index.isUnique());

                        if (index.getTtlExpireAfterSeconds() > 0) {
                            indexOptions.expireAfter(index.getTtlExpireAfterSeconds(), TimeUnit.SECONDS);
                        }

                        if (index.isAscending()) {
                            collection.createIndex(Indexes.ascending(fieldName), indexOptions);
                        } else {
                            collection.createIndex(Indexes.descending(fieldName), indexOptions);
                        }
                    }
                }
            }

            var indexTextDefMap = entityDef.getIndexTextDefMap();
            if (CollectionUtils.isNotEmpty(indexTextDefMap)) {
                AssertionUtils.isTrue(indexTextDefMap.size() == 1
                        , StringUtils.format("???????????????text??????[{}]???????????????", JsonUtils.object2String(indexTextDefMap.keySet())));
                var collection = getCollection(entityDef.getClazz());
                for (var indexTextDef : indexTextDefMap.entrySet()) {
                    var fieldName = indexTextDef.getKey();
                    var hasIndex = false;
                    for (var document : collection.listIndexes()) {
                        var keyDocument = (Document) document.get("key");
                        if (keyDocument.containsKey(fieldName)) {
                            hasIndex = true;
                        }
                    }
                    if (!hasIndex) {
                        collection.createIndex(Indexes.text(fieldName));
                    }
                }
            }
        }
    }

    @Override
    public void inject() {
        var applicationContext = OrmContext.getApplicationContext();
        var beanNames = applicationContext.getBeanDefinitionNames();

        for (var beanName : beanNames) {
            var bean = applicationContext.getBean(beanName);

            ReflectionUtils.filterFieldsInClass(bean.getClass()
                    , field -> field.isAnnotationPresent(EntityCachesInjection.class)
                    , field -> {
                        Type type = field.getGenericType();

                        if (!(type instanceof ParameterizedType)) {
                            throw new RuntimeException(StringUtils.format("??????[{}]????????????????????????", field.getName()));
                        }

                        Type[] types = ((ParameterizedType) type).getActualTypeArguments();
                        Class<? extends IEntity<?>> entityClazz = (Class<? extends IEntity<?>>) types[1];
                        IEntityCaches<?, ?> entityCaches = entityCachesMap.get(entityClazz);

                        if (entityCaches == null) {
                            throw new RunException("?????????????????????????????????????????????[entity-package:{}]???[entityCaches:{}]?????????????????????", ormConfig.getEntityPackage(), entityClazz);
                        }

                        ReflectionUtils.makeAccessible(field);
                        ReflectionUtils.setField(field, bean, entityCaches);
                        allEntityCachesUsableMap.put(entityClazz, true);
                    });
        }
    }

    @Override
    public void initAfter() {
        allEntityCachesUsableMap.entrySet().stream()
                .filter(it -> !it.getValue())
                .map(it -> it.getKey())
                .forEach(it -> entityCachesMap.remove(it));
    }

    @Override
    public <E extends IEntity<?>> IEntityCaches<?, E> getEntityCaches(Class<E> clazz) {
        var usable = allEntityCachesUsableMap.get(clazz);
        if (usable == null) {
            throw new RunException("????????????[]???EntityCaches???????????????", clazz.getCanonicalName());
        }
        if (!usable) {
            throw new RunException("Orm????????????[]???EntityCaches??????????????????????????????????????????????????????EntityCachesInjection?????????Entity?????????????????????", clazz.getCanonicalName());
        }
        return (IEntityCaches<?, E>) entityCachesMap.get(clazz);
    }

    @Override
    public Collection<IEntityCaches<?, ?>> getAllEntityCaches() {
        return Collections.unmodifiableCollection(entityCachesMap.values());
    }

    @Override
    public ClientSession getClientSession() {
        return mongoClient.startSession();
    }

    @Override
    public <E extends IEntity<?>> MongoCollection<E> getCollection(Class<E> entityClazz) {
        var collectionName = collectionNameMap.get(entityClazz);
        if (collectionName == null) {
            collectionName = StringUtils.substringBeforeLast(StringUtils.uncapitalize(entityClazz.getSimpleName()), "Entity");
            collectionNameMap.put(entityClazz, collectionName);
        }
        return mongodbDatabase.getCollection(collectionName, entityClazz);
    }


    @Override
    public MongoCollection<Document> getCollection(String collection) {
        return mongodbDatabase.getCollection(collection);
    }

    private Map<Class<? extends IEntity<?>>, EntityDef> scanEntityClass() {
        var cacheDefMap = new HashMap<Class<? extends IEntity<?>>, EntityDef>();

        var locationSet = scanEntityCacheAnno(ormConfig.getEntityPackage());
        for (var location : locationSet) {
            Class<? extends IEntity<?>> entityClazz;
            try {
                entityClazz = (Class<? extends IEntity<?>>) Class.forName(location);
            } catch (ClassNotFoundException e) {
                throw new RunException("?????????????????????[{}]", location);
            }
            var cacheDef = parserEntityDef(entityClazz);
            var previousCacheDef = cacheDefMap.putIfAbsent(entityClazz, cacheDef);
            AssertionUtils.isNull(previousCacheDef, "?????????????????????????????????[class:{}]", entityClazz.getSimpleName());
        }
        return cacheDefMap;
    }

    private Set<String> scanEntityCacheAnno(String scanLocation) {
        var prefixPattern = "classpath*:";
        var suffixPattern = "**/*.class";


        var resourcePatternResolver = new PathMatchingResourcePatternResolver();
        var metadataReaderFactory = new CachingMetadataReaderFactory(resourcePatternResolver);
        try {
            String packageSearchPath = prefixPattern + scanLocation.replace(StringUtils.PERIOD, StringUtils.SLASH) + StringUtils.SLASH + suffixPattern;
            Resource[] resources = resourcePatternResolver.getResources(packageSearchPath);
            Set<String> result = new HashSet<>();
            String name = EntityCache.class.getName();
            for (Resource resource : resources) {
                if (resource.isReadable()) {
                    MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(resource);
                    AnnotationMetadata annoMeta = metadataReader.getAnnotationMetadata();
                    if (annoMeta.hasAnnotation(name)) {
                        ClassMetadata clazzMeta = metadataReader.getClassMetadata();
                        result.add(clazzMeta.getClassName());
                    }
                }
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("????????????????????????:" + e);
        }
    }


    public EntityDef parserEntityDef(Class<? extends IEntity<?>> clazz) {
        analyze(clazz);

        var cacheStrategies = ormConfig.getCaches();
        var persisterStrategies = ormConfig.getPersisters();

        var entityCache = clazz.getAnnotation(EntityCache.class);
        var cache = entityCache.cache();
        var cacheStrategyOptional = cacheStrategies.stream().filter(it -> it.getStrategy().equals(cache.value())).findFirst();
        AssertionUtils.isTrue(cacheStrategyOptional.isPresent(), "?????????Entity[{}]????????????????????????[{}]", clazz.getSimpleName(), cache.value());

        var cacheStrategy = cacheStrategyOptional.get();
        var cacheSize = cacheStrategy.getSize();
        var expireMillisecond = cacheStrategy.getExpireMillisecond();

        var idField = ReflectionUtils.getFieldsByAnnoInPOJOClass(clazz, Id.class)[0];
        ReflectionUtils.makeAccessible(idField);

        var persister = entityCache.persister();
        var persisterStrategyOptional = persisterStrategies.stream().filter(it -> it.getStrategy().equals(persister.value())).findFirst();
        AssertionUtils.isTrue(persisterStrategyOptional.isPresent(), "?????????Entity[{}]???????????????????????????[{}]", clazz.getSimpleName(), persister.value());

        var persisterStrategy = persisterStrategyOptional.get();
        var indexDefMap = new HashMap<String, IndexDef>();
        var fields = ReflectionUtils.getFieldsByAnnoInPOJOClass(clazz, Index.class);
        for (var field : fields) {
            var indexAnnotation = field.getAnnotation(Index.class);

            if (indexAnnotation.ttlExpireAfterSeconds() > 0) {
                var fieldType = field.getGenericType();
                if (!(fieldType == Date.class || field.getGenericType().toString().equals("java.util.List<java.util.Date>"))) {
                    throw new IllegalArgumentException(StringUtils.format("MongoDB??????TTL??????[{}]?????????Date???List<Date>?????????????????????", field.getName()));
                }
            }

            IndexDef indexDef = new IndexDef(field, indexAnnotation.ascending(), indexAnnotation.unique(), indexAnnotation.ttlExpireAfterSeconds());
            indexDefMap.put(field.getName(), indexDef);
        }

        var indexTextDefMap = new HashMap<String, IndexTextDef>();
        fields = ReflectionUtils.getFieldsByAnnoInPOJOClass(clazz, IndexText.class);
        for (var field : fields) {
            IndexTextDef indexTextDef = new IndexTextDef(field, field.getAnnotation(IndexText.class));
            indexTextDefMap.put(field.getName(), indexTextDef);
        }

        return EntityDef.valueOf(idField, clazz, cacheSize, expireMillisecond, persisterStrategy, indexDefMap, indexTextDefMap);
    }

    private void analyze(Class<?> clazz) {
        // ???????????????IEntity??????
        AssertionUtils.isTrue(IEntity.class.isAssignableFrom(clazz), "???[{}]????????????????????????[{}]??????????????????[{}]", EntityCache.class.getName(), clazz.getCanonicalName(), IEntity.class.getCanonicalName());
        // ?????????Entity???????????????EntityCache??????
        AssertionUtils.notNull(clazz.getAnnotation(EntityCache.class), "?????????Entity[{}]???????????????[{}]??????", clazz.getCanonicalName(), EntityCache.class.getName());

        // ??????entity??????
        var entitySubClassMap = new HashMap<Class<?>, Set<Class<?>>>();
        checkEntity(clazz);
        // ????????????????????????
        for (var entry : entitySubClassMap.entrySet()) {
            var subClass = entry.getKey();
            var subClassSet = entry.getValue();
            if (subClassSet.contains(subClass)) {
                throw new RunException("ORM[class:{}]????????????????????????????????????[class:{}]", clazz.getSimpleName(), subClass.getSimpleName());
            }

            var queue = new LinkedList<>(subClassSet);
            var allSubClassSet = new HashSet<>(queue);
            while (!queue.isEmpty()) {
                var firstSubClass = queue.poll();
                if (entitySubClassMap.containsKey(firstSubClass)) {
                    for (var elementClass : entitySubClassMap.get(firstSubClass)) {
                        if (subClass.equals(elementClass)) {
                            throw new RunException("ORM[class:{}]???????????????[class:{}]????????????????????????[class:{}]", clazz.getSimpleName(), elementClass.getSimpleName(), elementClass.getSimpleName());
                        }

                        if (!allSubClassSet.contains(elementClass)) {
                            allSubClassSet.add(elementClass);
                            queue.offer(elementClass);
                        }
                    }
                }
            }
        }

        // ??????id?????????id()???????????????
        var idFields = ReflectionUtils.getFieldsByAnnoInPOJOClass(clazz, Id.class);
        AssertionUtils.isTrue(ArrayUtils.isNotEmpty(idFields) && idFields.length == 1
                , "?????????Entity[{}]???????????????????????????Id??????????????????????????????Id?????????????????????????????????Storage???Id?????????", clazz.getSimpleName());
        var idField = idFields[0];
        // idField?????????private??????
        AssertionUtils.isTrue(Modifier.isPrivate(idField.getModifiers()), "?????????Entity[{}]???id?????????private?????????", clazz.getSimpleName());

        // ?????????id???????????????????????????id()??????????????????????????????????????????????????????????????????
        var entityInstance = ReflectionUtils.newInstance(clazz);
        var idFieldType = idField.getType();
        Object idFiledValue = null;
        if (idFieldType.equals(int.class) || idFieldType.equals(Integer.class)) {
            idFiledValue = RandomUtils.randomInt();
        } else if (idFieldType.equals(long.class) || idFieldType.equals(Long.class)) {
            idFiledValue = RandomUtils.randomLong();
        } else if (idFieldType.equals(float.class) || idFieldType.equals(Float.class)) {
            idFiledValue = (float) RandomUtils.randomDouble();
        } else if (idFieldType.equals(double.class) || idFieldType.equals(Double.class)) {
            idFiledValue = RandomUtils.randomDouble();
        } else if (idFieldType.equals(String.class)) {
            idFiledValue = RandomUtils.randomString(10);
        } else {
            throw new RunException("orm???????????????int long float double String");
        }

        ReflectionUtils.makeAccessible(idField);
        ReflectionUtils.setField(idField, entityInstance, idFiledValue);
        var idMethodOptional = Arrays.stream(ReflectionUtils.getMethodsByNameInPOJOClass(clazz, "id"))
                .filter(it -> it.getParameterCount() <= 0)
                .findFirst();
        AssertionUtils.isTrue(idMethodOptional.isPresent(), "?????????Entity[{}]????????????id()??????", clazz.getSimpleName());
        var idMethod = idMethodOptional.get();
        ReflectionUtils.makeAccessible(idMethod);
        var idMethodReturnValue = ReflectionUtils.invokeMethod(entityInstance, idMethod);
        AssertionUtils.isTrue(idFiledValue.equals(idMethodReturnValue), "?????????Entity[{}]???id??????????????????[field:{}]???id??????????????????[method:{}]?????????????????????id()????????????????????????"
                , clazz.getSimpleName(), idFiledValue, idMethodReturnValue);

        // ??????gvs()?????????svs()???????????????
        var gvsMethodOptional = Arrays.stream(ReflectionUtils.getAllMethods(clazz))
                .filter(it -> it.getName().equals("gvs"))
                .filter(it -> it.getParameterCount() <= 0)
                .findFirst();

        var svsMethodOptional = Arrays.stream(ReflectionUtils.getAllMethods(clazz))
                .filter(it -> it.getName().equals("svs"))
                .filter(it -> it.getParameterCount() == 1)
                .filter(it -> it.getParameterTypes()[0].equals(long.class))
                .findFirst();
        // gvs???svs??????????????????????????????????????????
        if (gvsMethodOptional.isEmpty() || svsMethodOptional.isEmpty()) {
            AssertionUtils.isTrue(gvsMethodOptional.isEmpty() && svsMethodOptional.isEmpty(), "?????????Entity[{}]???gvs???svs????????????????????????????????????????????????", clazz.getSimpleName());
            return;
        }

        var gvsMethod = gvsMethodOptional.get();
        var svsMethod = svsMethodOptional.get();
        var vsValue = RandomUtils.randomLong();
        ReflectionUtils.invokeMethod(entityInstance, svsMethod, vsValue);
        var gvsReturnValue = ReflectionUtils.invokeMethod(entityInstance, gvsMethod);
        AssertionUtils.isTrue(gvsReturnValue.equals(vsValue), "?????????Entity[{}]???gvs?????????svs???????????????????????????", clazz.getSimpleName());
    }

    private void checkEntity(Class<?> clazz) {
        // ????????????????????????javabean???????????????????????????????????????????????????????????????????????????????????????po?????????
        ReflectionUtils.assertIsPojoClass(clazz);
        // ??????????????????
        AssertionUtils.isTrue(ArrayUtils.isEmpty(clazz.getTypeParameters()), "[class:{}]??????????????????", clazz.getCanonicalName());
        // ?????????????????????????????????
        ReflectionUtils.publicEmptyConstructor(clazz);

        // ????????????Storage???Index??????
        var storageIndexes = ReflectionUtils.getFieldsByAnnoNameInPOJOClass(clazz, "com.zfoo.storage.model.anno.Index");
        if (ArrayUtils.isNotEmpty(storageIndexes)) {
            throw new RunException("???Orm???????????????Orm???Index?????????????????????Storage???Index???????????????????????????????????????????????????????????????????????????????????????");
        }

        var filedList = ReflectionUtils.notStaticAndTransientFields(clazz);

        for (var field : filedList) {
            // entity?????????????????????get???set??????
            ReflectionUtils.fieldToGetMethod(clazz, field);
            ReflectionUtils.fieldToSetMethod(clazz, field);

            // ???????????????????????????
            var fieldType = field.getType();
            if (isBaseType(fieldType)) {
                // do nothing
            } else if (fieldType.isArray()) {
                // ???????????????
                Class<?> arrayClazz = fieldType.getComponentType();
                checkSubEntity(clazz, arrayClazz);
            } else if (Set.class.isAssignableFrom(fieldType)) {
                AssertionUtils.isTrue(fieldType.equals(Set.class), "ORM[class:{}]?????????????????????????????????Set????????????", clazz.getCanonicalName());

                var type = field.getGenericType();
                AssertionUtils.isTrue(type instanceof ParameterizedType, "ORM[class:{}]???????????????????????????????????????[field:{}]", clazz.getCanonicalName(), field.getName());

                var types = ((ParameterizedType) type).getActualTypeArguments();
                AssertionUtils.isTrue(types.length == 1, "ORM[class:{}]???Set????????????????????????[field:{}]?????????????????????", clazz.getCanonicalName(), field.getName());

                checkSubEntity(clazz, types[0]);
            } else if (List.class.isAssignableFrom(fieldType)) {
                // ?????????List
                AssertionUtils.isTrue(fieldType.equals(List.class), "ORM[class:{}]?????????????????????????????????List????????????", clazz.getCanonicalName());

                var type = field.getGenericType();
                AssertionUtils.isTrue(type instanceof ParameterizedType, "ORM[class:{}]???????????????????????????????????????[field:{}]", clazz.getCanonicalName(), field.getName());

                var types = ((ParameterizedType) type).getActualTypeArguments();
                AssertionUtils.isTrue(types.length == 1, "ORM[class:{}]???List????????????????????????[field:{}]?????????????????????", clazz.getCanonicalName(), field.getName());

                checkSubEntity(clazz, types[0]);
            } else if (Map.class.isAssignableFrom(fieldType)) {
                if (!fieldType.equals(Map.class)) {
                    throw new RunException("ORM[class:{}]?????????????????????????????????Map????????????", clazz.getCanonicalName());
                }

                var type = field.getGenericType();

                if (!(type instanceof ParameterizedType)) {
                    throw new RunException("ORM[class:{}]?????????????????????????????????[field:{}]???????????????", clazz.getCanonicalName(), field.getName());
                }

                var types = ((ParameterizedType) type).getActualTypeArguments();

                if (types.length != 2) {
                    throw new RunException("ORM[class:{}]?????????????????????????????????[field:{}]?????????????????????", clazz.getCanonicalName(), field.getName());
                }

                var keyType = types[0];
                var valueType = types[1];

                if (!String.class.isAssignableFrom((Class<?>) keyType)) {
                    throw new RunException("ORM[class:{}]????????????????????????Map???key???????????????String??????", clazz.getCanonicalName());
                }

                checkSubEntity(clazz, valueType);
            } else {
                checkEntity(fieldType);
            }
        }
    }


    private void checkSubEntity(Class<?> currentEntityClass, Type type) {
        if (type instanceof ParameterizedType) {
            // ?????????
            Class<?> clazz = (Class<?>) ((ParameterizedType) type).getRawType();
            if (Set.class.equals(clazz)) {
                // Set<Set<String>>
                checkSubEntity(currentEntityClass, ((ParameterizedType) type).getActualTypeArguments()[0]);
                return;
            } else if (List.class.equals(clazz)) {
                // List<List<String>>
                checkSubEntity(currentEntityClass, ((ParameterizedType) type).getActualTypeArguments()[0]);
                return;
            } else if (Map.class.equals(clazz)) {
                // Map<List<String>, List<String>>
                var types = ((ParameterizedType) type).getActualTypeArguments();
                var keyType = types[0];
                var valueType = types[1];
                if (!String.class.isAssignableFrom((Class<?>) keyType)) {
                    throw new RunException("ORM???Map???key?????????String??????");
                }
                checkSubEntity(currentEntityClass, valueType);
                return;
            }
        } else if (type instanceof Class) {
            Class<?> clazz = ((Class<?>) type);
            if (isBaseType(clazz)) {
                // do nothing
                return;
            } else if (clazz.getComponentType() != null) {
                // ???????????????????????????
                throw new RunException("ORM??????????????????????????????????????????[type:{}]??????????????????????????????", type);
            } else if (clazz.equals(List.class) || clazz.equals(Set.class) || clazz.equals(Map.class)) {
                throw new RunException("ORM????????????????????????????????????[type:{}]??????", type);
            } else {
                checkEntity(clazz);
                return;
            }
        }
        throw new RunException("[type:{}]???????????????", type);
    }

    private boolean isBaseType(Class<?> clazz) {
        return clazz.isPrimitive() || Number.class.isAssignableFrom(clazz) || String.class.isAssignableFrom(clazz);
    }
}
