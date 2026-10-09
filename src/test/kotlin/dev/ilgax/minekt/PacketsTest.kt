package dev.ilgax.minekt

import dev.ilgax.minekt.job.JobManager
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.common.ServerboundPongPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Packet filters and listeners, fed packets the way the connection mixin does. */
class PacketsTest {
    private val host = TestHost()
    private val jobs = JobManager(host)

    @Test
    fun `filters replace and drop, listeners see the result`() {
        jobs.eval(
            """
            import net.minecraft.network.protocol.common.*
            interceptPacket<ClientboundPingPacket> { if (it.id == 0) null else ClientboundPingPacket(it.id + 1) }
            onPacket<ClientboundPingPacket> { echo("got ping " + it.id) }
            onPacketSent<ServerboundPongPacket> { echo("sent pong " + it.id) }
            launch { echo("awaited " + awaitPacket<ServerboundPongPacket>(sent = true).id) }
            echo("ready")
            """.trimIndent(),
        )
        assertEquals("ready", host.next())
        Thread.sleep(200) // let the coroutine start waiting

        val replaced = jobs.filterPacket(ClientboundPingPacket(41), incoming = true)
        assertEquals(42, (replaced as ClientboundPingPacket).id)
        assertEquals("got ping 42", host.next())

        assertNull(jobs.filterPacket(ClientboundPingPacket(0), incoming = true))

        val pong = ServerboundPongPacket(7)
        assertSame(pong, jobs.filterPacket(pong, incoming = false))
        val messages = setOf(host.next(), host.next())
        assertEquals(setOf("sent pong 7", "awaited 7"), messages)
    }

    @Test
    fun `a throwing filter is removed and the packet passes`() {
        jobs.eval(
            """
            import net.minecraft.network.protocol.common.*
            interceptPacket<ClientboundPingPacket> { error("broken filter") }
            onPacket<ClientboundPingPacket> { }
            echo("ready")
            """.trimIndent(),
        )
        assertEquals("ready", host.next())
        val ping = ClientboundPingPacket(1)
        assertSame(ping, jobs.filterPacket(ping, incoming = true))
        assertTrue("broken filter" in host.next())
        assertSame(ping, jobs.filterPacket(ping, incoming = true))
    }
}
