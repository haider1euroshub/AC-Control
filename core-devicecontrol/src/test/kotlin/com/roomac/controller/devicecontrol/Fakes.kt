package com.roomac.controller.devicecontrol

/** Answers by "METHOD url" (exact) or by a SOAP action/body fragment; records every request. */
class FakeHttp : LanHttp {
    data class Req(val method: String, val url: String, val headers: Map<String, String>, val body: String)
    val requests = mutableListOf<Req>()
    val routes = mutableListOf<Pair<(Req) -> Boolean, (Req) -> HttpReply>>()

    fun on(method: String, url: String, reply: (Req) -> HttpReply) { routes += ({ r: Req -> r.method == method && r.url == url } to reply) }
    fun onBody(url: String, fragment: String, reply: (Req) -> HttpReply) { routes += ({ r: Req -> r.url == url && r.body.contains(fragment) } to reply) }

    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpReply {
        val r = Req(method, url, headers, body?.toString(Charsets.UTF_8).orEmpty())
        requests += r
        return routes.lastOrNull { it.first(r) }?.second?.invoke(r) ?: HttpReply(404, emptyMap(), ByteArray(0))
    }

    companion object {
        fun ok(text: String, headers: Map<String, String> = emptyMap()) = HttpReply(200, headers, text.toByteArray())
        fun status(code: Int, headers: Map<String, String> = emptyMap()) = HttpReply(code, headers, ByteArray(0))
    }
}
