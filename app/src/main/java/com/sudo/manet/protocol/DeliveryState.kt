package com.sudo.manet.protocol

enum class DeliveryState {
    PENDING,        // sent, waiting for ACK
    DELIVERED,      // ACK received
    FAILED,         // route failed or max retries exceeded
    EXPIRED,        // TTL reached zero
    DUPLICATE       // already seen this packetId
}