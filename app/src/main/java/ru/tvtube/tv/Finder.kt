package ru.tvtube.tv

import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Ищет сервер TV Tube в домашней сети: перебирает адреса вида 192.168.x.1-254 на портах сайта. Вводить адрес руками не нужно. */
object Finder {
    private val PORTS = intArrayOf(8000, 8080)

    private fun prefixes(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in all) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val ip = a.hostAddress ?: continue
                        out.add(ip.substringBeforeLast('.'))
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out.toList()
    }

    private fun probe(host: String, port: Int): Boolean {
        return try {
            val c = URL("http://$host:$port/api/config").openConnection() as HttpURLConnection
            c.connectTimeout = 700
            c.readTimeout = 1500
            c.requestMethod = "GET"
            if (c.responseCode != 200) return false
            val body = c.inputStream.bufferedReader().use { it.readText() }
            body.contains("\"sources\"")
        } catch (e: Exception) {
            false
        }
    }

    /** Возвращает адрес вроде http://192.168.1.50:8000 или null. Вызывать НЕ из главного потока. */
    fun scan(): String? {
        val prefs = prefixes()
        if (prefs.isEmpty()) return null
        val jobs = ArrayList<Pair<String, Int>>()
        for (p in prefs) for (port in PORTS) for (i in 1..254) jobs.add(Pair("$p.$i", port))
        val pool = Executors.newFixedThreadPool(48)
        val found = AtomicReference<String?>(null)
        val latch = CountDownLatch(jobs.size)
        for ((host, port) in jobs) {
            pool.execute {
                try {
                    if (found.get() == null && probe(host, port)) found.compareAndSet(null, "http://$host:$port")
                } finally {
                    latch.countDown()
                }
            }
        }
        val t0 = System.currentTimeMillis()
        while (found.get() == null && latch.count > 0 && System.currentTimeMillis() - t0 < 40000) Thread.sleep(60)
        pool.shutdownNow()
        return found.get()
    }
}
