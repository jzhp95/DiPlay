package com.shilapi.xcertplay.airplay

/** A control-session address without opening a network connection for stream-ownership fixtures. */
internal class LoopbackControlSocket : java.net.Socket() {
    override fun getInetAddress()=java.net.InetAddress.getLoopbackAddress()
    override fun getLocalAddress()=java.net.InetAddress.getLoopbackAddress()
    override fun getRemoteSocketAddress()=java.net.InetSocketAddress(inetAddress,12345)
}
