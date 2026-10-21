package com.tabdraw.native_client

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * StylusPacket — 極簡固定長度二進位封包定義
 *
 * 封包格式（共 20 Bytes）：
 * ┌────────┬──────────┬──────┬──────┬──────────┬──────────┬──────────┬──────────┐
 * │ Header │ Seq(2B)  │ Flag │  X   │    Y     │ Pressure │   Tilt   │ Checksum │
 * │  2B    │          │  1B  │  4B  │    4B    │    4B    │    4B    │    1B    │
 * └────────┴──────────┴──────┴──────┴──────────┴──────────┴──────────┴──────────┘
 *   Total: 2 + 1 + 4 + 4 + 4 + 4 + 1 = 20 Bytes
 *
 * Flag 定義：
 *   0x00 = Hover (Air)
 *   0x01 = Down  (Touch Begin)
 *   0x02 = Move  (Touch Continue)
 *   0x03 = Up    (Touch End)
 *
 * Header: 0xAB 0xCD（魔術字節，接收端用於同步對齊）
 * Checksum: 所有欄位 XOR
 */
data class StylusPacket(
    val flag: Byte,       // 0=Hover, 1=Down, 2=Move, 3=Up
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tilt: Float
) {
    companion object {
        const val PACKET_SIZE = 22
        const val HEADER_BYTE_0: Byte = 0xAB.toByte()
        const val HEADER_BYTE_1: Byte = 0xCD.toByte()

        // Flag 常數
        const val FLAG_HOVER: Byte = 0x00
        const val FLAG_DOWN:  Byte = 0x01
        const val FLAG_MOVE:  Byte = 0x02
        const val FLAG_UP:    Byte = 0x03

        private var seqCounter: Short = 0

        /**
         * 從 ByteArray 解析封包（接收端用）
         */
        fun fromBytes(data: ByteArray): StylusPacket? {
            if (data.size < PACKET_SIZE) return null
            if (data[0] != HEADER_BYTE_0 || data[1] != HEADER_BYTE_1) return null

            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            buf.getShort() // header
            buf.getShort()  // seq（保留供日後亂序修復，暫時跳過）
            val flag   = buf.get()
            val x      = buf.getFloat()
            val y      = buf.getFloat()
            val pressure = buf.getFloat()
            val tilt   = buf.getFloat()
            val chk    = buf.get()

            // 驗證 checksum
            var xorVal: Byte = 0
            for (i in 0 until PACKET_SIZE - 1) xorVal = (xorVal.toInt() xor data[i].toInt()).toByte()
            if (xorVal != chk) return null

            return StylusPacket(flag, x, y, pressure, tilt)
        }
    }

    /**
     * 序列化為 ByteArray，供 UDP 直接傳送
     * Little-Endian / Big-Endian 均已透過常數定義，預設 BIG_ENDIAN
     */
    fun toBytes(): ByteArray {
        val seq = synchronized(StylusPacket) { seqCounter++ }
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(HEADER_BYTE_0)
        buf.put(HEADER_BYTE_1)
        buf.putShort(seq)
        buf.put(flag)
        buf.putFloat(x)
        buf.putFloat(y)
        buf.putFloat(pressure)
        buf.putFloat(tilt)

        // Checksum：前 19 Bytes XOR
        val arr = buf.array()
        var xorVal: Byte = 0
        for (i in 0 until PACKET_SIZE - 1) xorVal = (xorVal.toInt() xor arr[i].toInt()).toByte()
        buf.put(xorVal)

        return buf.array()
    }
}
