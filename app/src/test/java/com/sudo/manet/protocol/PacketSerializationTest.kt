package com.sudo.manet.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.*

class PacketSerializationTest {

    @Test
    fun testPacketSerialization() {
        val originalPacket = Packet(
            type = PacketType.MSG_DIRECT,
            senderId = "SENDER",
            destId = "DEST",
            ttl = 5,
            payload = "Hello World",
            sequenceNumber = 42
        )

        // Serialize
        val baos = ByteArrayOutputStream()
        val oos = ObjectOutputStream(baos)
        oos.writeObject(originalPacket)
        oos.close()

        val bytes = baos.toByteArray()

        // Deserialize
        val bais = ByteArrayInputStream(bytes)
        val ois = ObjectInputStream(bais)
        val deserializedPacket = ois.readObject() as Packet
        ois.close()

        assertEquals(originalPacket.packetId, deserializedPacket.packetId)
        assertEquals(originalPacket.type, deserializedPacket.type)
        assertEquals(originalPacket.senderId, deserializedPacket.senderId)
        assertEquals(originalPacket.destId, deserializedPacket.destId)
        assertEquals(originalPacket.ttl, deserializedPacket.ttl)
        assertEquals(originalPacket.payload, deserializedPacket.payload)
        assertEquals(originalPacket.sequenceNumber, deserializedPacket.sequenceNumber)
    }
}
