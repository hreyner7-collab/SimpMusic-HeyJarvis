package com.maxrave.simpmusic.smarthome

import kotlinx.serialization.Serializable

@Serializable
data class SmartSpeakerDevice(
    val udn: String,
    val friendlyName: String,
    val manufacturer: String = "",
    val modelName: String = "",
    val locationUrl: String,
    val host: String,
    val avTransportControlUrl: String,
    val renderingControlUrl: String? = null,
    val lastSeenMs: Long = 0L,
) {
    val ecosystemLabel: String
        get() =
            when {
                manufacturer.contains("Sonos", ignoreCase = true) || friendlyName.contains("Sonos", ignoreCase = true) -> "Sonos"
                manufacturer.contains("Bose", ignoreCase = true) -> "Bose"
                manufacturer.contains("Denon", ignoreCase = true) || manufacturer.contains("Marantz", ignoreCase = true) || modelName.contains("HEOS", ignoreCase = true) -> "HEOS"
                manufacturer.contains("Yamaha", ignoreCase = true) -> "MusicCast"
                manufacturer.contains("WiiM", ignoreCase = true) || manufacturer.contains("Linkplay", ignoreCase = true) -> "WiiM / Linkplay"
                manufacturer.contains("Samsung", ignoreCase = true) -> "Samsung"
                manufacturer.contains("LG", ignoreCase = true) -> "LG"
                manufacturer.contains("Sony", ignoreCase = true) -> "Sony"
                manufacturer.isNotBlank() -> manufacturer
                else -> "Smart Speaker (DLNA)"
            }
}

data class SmartSpeakerPlaybackState(
    val activeSpeaker: SmartSpeakerDevice? = null,
    val isPlaying: Boolean = false,
    val trackTitle: String = "",
    val trackArtist: String = "",
    val volumePercent: Int = 50,
    val isScanning: Boolean = false,
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
) {
    val isCastingToSpeaker: Boolean get() = activeSpeaker != null
}

data class SsdpDiscoveryResponse(
    val location: String,
    val usn: String = "",
    val searchTarget: String = "",
    val server: String = "",
)

object SsdpProtocol {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val MULTICAST_PORT = 1900
    const val ST_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val ST_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    fun buildMSearchPayload(
        searchTarget: String = ST_MEDIA_RENDERER,
        mxSeconds: Int = 2,
    ): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $MULTICAST_ADDRESS:$MULTICAST_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: ${mxSeconds.coerceIn(1, 5)}\r\n" +
            "ST: $searchTarget\r\n" +
            "USER-AGENT: SimpMusic/2.6 UPnP/1.1\r\n\r\n"

    fun parseSsdpResponse(raw: String): SsdpDiscoveryResponse? {
        val lines = raw.split("\r\n", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                headers[key] = value
            }
        }
        val location = headers["location"] ?: return null
        if (!location.startsWith("http://", ignoreCase = true) && !location.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return SsdpDiscoveryResponse(
            location = location,
            usn = headers["usn"].orEmpty(),
            searchTarget = headers["st"].orEmpty(),
            server = headers["server"].orEmpty(),
        )
    }
}

object UpnpDeviceParser {
    private val SERVICE_BLOCK_REGEX = Regex("<service\\b[^>]*>(.*?)</service>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun parseDeviceDescriptionXml(
        locationUrl: String,
        xml: String,
        seenAtMs: Long = 0L,
    ): SmartSpeakerDevice? {
        val urlBase = extractTag(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: locationUrl
        var avTransportPath: String? = null
        var renderingControlPath: String? = null

        for (match in SERVICE_BLOCK_REGEX.findAll(xml)) {
            val block = match.groupValues[1]
            val serviceType = extractTag(block, "serviceType").orEmpty()
            val controlUrl = extractTag(block, "controlURL").orEmpty()
            if (controlUrl.isBlank()) continue
            when {
                serviceType.contains("AVTransport", ignoreCase = true) && avTransportPath == null -> {
                    avTransportPath = controlUrl
                }
                serviceType.contains("RenderingControl", ignoreCase = true) && renderingControlPath == null -> {
                    renderingControlPath = controlUrl
                }
            }
        }

        val resolvedAvTransport = resolveUrl(urlBase, avTransportPath ?: return null) ?: return null
        val resolvedRendering = renderingControlPath?.let { resolveUrl(urlBase, it) }
        val friendlyName = unescapeXml(extractTag(xml, "friendlyName").orEmpty()).ifBlank { "Smart Speaker" }
        val manufacturer = unescapeXml(extractTag(xml, "manufacturer").orEmpty())
        val modelName = unescapeXml(extractTag(xml, "modelName").orEmpty())
        val udn = unescapeXml(extractTag(xml, "UDN").orEmpty()).ifBlank { locationUrl }
        val host = extractHost(locationUrl)

        return SmartSpeakerDevice(
            udn = udn,
            friendlyName = friendlyName,
            manufacturer = manufacturer,
            modelName = modelName,
            locationUrl = locationUrl,
            host = host,
            avTransportControlUrl = resolvedAvTransport,
            renderingControlUrl = resolvedRendering,
            lastSeenMs = seenAtMs,
        )
    }

    internal fun resolveUrl(
        baseUrl: String,
        pathOrUrl: String,
    ): String? {
        val clean = pathOrUrl.trim()
        if (clean.isEmpty()) return null
        if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            return clean
        }
        val schemeEnd = baseUrl.indexOf("://")
        if (schemeEnd <= 0) return null
        val authorityStart = schemeEnd + 3
        val pathStart = baseUrl.indexOf('/', authorityStart)
        val origin = if (pathStart > 0) baseUrl.substring(0, pathStart) else baseUrl.trimEnd('/')
        if (clean.startsWith("/")) {
            return "$origin$clean"
        }
        val baseDir =
            if (pathStart > 0) {
                val lastSlash = baseUrl.lastIndexOf('/')
                if (lastSlash >= authorityStart) baseUrl.substring(0, lastSlash + 1) else "$origin/"
            } else {
                "$origin/"
            }
        return "$baseDir$clean"
    }

    internal fun extractHost(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val authority = afterScheme.substringBefore('/')
        return authority.substringBefore(':')
    }

    private fun extractTag(
        xml: String,
        tagName: String,
    ): String? {
        val regex = Regex("<(?:[a-zA-Z0-9_-]+:)?$tagName\\b[^>]*>(.*?)</(?:[a-zA-Z0-9_-]+:)?$tagName>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()
    }

    fun unescapeXml(text: String): String =
        text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
}

object UpnpSoapBuilder {
    const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL_SERVICE = "urn:schemas-upnp-org:service:RenderingControl:1"

    fun soapActionHeader(
        serviceType: String,
        action: String,
    ): String = "\"$serviceType#$action\""

    fun escapeXml(raw: String): String =
        buildString(raw.length + 16) {
            for (ch in raw) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }

    fun buildDidlLiteMetadata(
        streamUrl: String,
        title: String,
        artist: String,
        album: String = "",
        artworkUrl: String = "",
    ): String {
        val safeTitle = escapeXml(title.ifBlank { "SimpMusic Audio" })
        val safeArtist = escapeXml(artist)
        val safeAlbum = escapeXml(album)
        val safeArt = escapeXml(artworkUrl)
        val safeUrl = escapeXml(streamUrl)
        return buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>").append(safeTitle).append("</dc:title>")
            if (safeArtist.isNotEmpty()) {
                append("<upnp:artist>").append(safeArtist).append("</upnp:artist>")
            }
            if (safeAlbum.isNotEmpty()) {
                append("<upnp:album>").append(safeAlbum).append("</upnp:album>")
            }
            if (safeArt.isNotEmpty()) {
                append("<upnp:albumArtURI>").append(safeArt).append("</upnp:albumArtURI>")
            }
            append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
            append("<res protocolInfo=\"http-get:*:audio/*:*\">").append(safeUrl).append("</res>")
            append("</item></DIDL-Lite>")
        }
    }

    fun buildSetAvTransportUriEnvelope(
        streamUrl: String,
        title: String,
        artist: String,
        album: String = "",
        artworkUrl: String = "",
    ): String {
        val didl = buildDidlLiteMetadata(streamUrl, title, artist, album, artworkUrl)
        return wrapEnvelope(
            serviceType = AV_TRANSPORT_SERVICE,
            action = "SetAVTransportURI",
            bodyXml =
                "<InstanceID>0</InstanceID>" +
                    "<CurrentURI>${escapeXml(streamUrl)}</CurrentURI>" +
                    "<CurrentURIMetaData>${escapeXml(didl)}</CurrentURIMetaData>",
        )
    }

    fun buildPlayEnvelope(): String =
        wrapEnvelope(
            serviceType = AV_TRANSPORT_SERVICE,
            action = "Play",
            bodyXml = "<InstanceID>0</InstanceID><Speed>1</Speed>",
        )

    fun buildPauseEnvelope(): String =
        wrapEnvelope(
            serviceType = AV_TRANSPORT_SERVICE,
            action = "Pause",
            bodyXml = "<InstanceID>0</InstanceID>",
        )

    fun buildStopEnvelope(): String =
        wrapEnvelope(
            serviceType = AV_TRANSPORT_SERVICE,
            action = "Stop",
            bodyXml = "<InstanceID>0</InstanceID>",
        )

    fun buildSeekEnvelope(positionMs: Long): String =
        wrapEnvelope(
            serviceType = AV_TRANSPORT_SERVICE,
            action = "Seek",
            bodyXml =
                "<InstanceID>0</InstanceID>" +
                    "<Unit>REL_TIME</Unit>" +
                    "<Target>${formatUpnpTime(positionMs)}</Target>",
        )

    fun buildSetVolumeEnvelope(volumePercent: Int): String =
        wrapEnvelope(
            serviceType = RENDERING_CONTROL_SERVICE,
            action = "SetVolume",
            bodyXml =
                "<InstanceID>0</InstanceID>" +
                    "<Channel>Master</Channel>" +
                    "<DesiredVolume>${volumePercent.coerceIn(0, 100)}</DesiredVolume>",
        )

    fun buildGetVolumeEnvelope(): String =
        wrapEnvelope(
            serviceType = RENDERING_CONTROL_SERVICE,
            action = "GetVolume",
            bodyXml = "<InstanceID>0</InstanceID><Channel>Master</Channel>",
        )

    fun parseGetVolumeResponse(xml: String): Int? {
        val match = Regex("<CurrentVolume\\b[^>]*>(\\d+)</CurrentVolume>", RegexOption.IGNORE_CASE).find(xml)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
    }

    fun formatUpnpTime(positionMs: Long): String {
        val totalSec = (positionMs.coerceAtLeast(0L) / 1000L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }

    private fun wrapEnvelope(
        serviceType: String,
        action: String,
        bodyXml: String,
    ): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$serviceType\">$bodyXml</u:$action></s:Body>" +
            "</s:Envelope>"
}
