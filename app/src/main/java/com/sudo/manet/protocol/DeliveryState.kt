package com.sudo.manet.protocol

import java.io.Serializable

enum class DeliveryState : Serializable {
    PENDING,        // sent, waiting for route
    BUFFERED,       // in relay queue / waiting to be carried
    DELIVERED,      // ACK received
    FAILED,         // route failed or max retries exceeded
    EXPIRED,        // TTL reached zero
    DUPLICATE       // already seen this packetId
}