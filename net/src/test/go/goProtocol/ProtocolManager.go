package protocol

func init() {
	Protocols[100] = new(Message)
    Protocols[101] = new(Error)
    Protocols[102] = new(Heartbeat)
    Protocols[103] = new(Ping)
    Protocols[104] = new(Pong)
    Protocols[111] = new(PairLong)
    Protocols[112] = new(PairString)
    Protocols[113] = new(PairLS)
    Protocols[114] = new(TripleLong)
    Protocols[115] = new(TripleString)
    Protocols[116] = new(TripleLSS)
    Protocols[1200] = new(UdpHelloRequest)
    Protocols[1201] = new(UdpHelloResponse)
    Protocols[1300] = new(TcpHelloRequest)
    Protocols[1301] = new(TcpHelloResponse)
    Protocols[1500] = new(JProtobufHelloRequest)
    Protocols[1501] = new(JProtobufHelloResponse)
    Protocols[1600] = new(JsonHelloRequest)
    Protocols[1601] = new(JsonHelloResponse)
    Protocols[5000] = new(GatewayToProviderRequest)
    Protocols[5001] = new(GatewayToProviderResponse)
}
