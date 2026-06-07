package com.sudo.manet.protocol

enum class PacketType {
    MSG_GOSSIP,     // broadcast — handled by GossipRouter
    MSG_DIRECT,     // unicast — handled by AodvRouter
    RREQ,           // route request — flooded to find destination
    RREP,           // route reply — sent back along reverse path
    RERR,           // route error — notified when a link breaks
    LSA,            // link-state advertisement — for Dijkstra
    ACK             // acknowledgement — confirms direct delivery
}