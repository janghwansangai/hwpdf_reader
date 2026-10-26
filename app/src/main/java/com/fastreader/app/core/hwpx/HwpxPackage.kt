package com.fastreader.app.core.hwpx

/** Contents/content.hpf (OPF): 항목 ID ↔ 경로, 구역 순서 */
internal class HwpxManifest {
    class Item(val id: String, val href: String, val mediaType: String)

    val items = ArrayList<Item>()
    val spine = ArrayList<String>()

    private val byId = HashMap<String, Item>()
    private val byHref = HashMap<String, Item>()

    fun itemById(id: String): Item? = byId[id]

    /** zip 안 경로(정규화)로 항목 찾기 */
    fun itemByPath(path: String): Item? = byHref[normalize(path)]

    /** spine 순서의 경로 목록 (정규화) */
    fun spinePaths(): List<String> = spine.mapNotNull { byId[it]?.href }.map { normalize(it) }

    val root: XNode = object : XNode() {
        override fun child(name: String, a: XAttrs): XNode? = if (name == "package") PackageNode() else throw StopParsingException()
    }

    private inner class PackageNode : XNode() {
        override fun child(name: String, a: XAttrs): XNode? = when (name) {
            "manifest" -> object : XNode() {
                override fun child(name: String, a: XAttrs): XNode? {
                    if (name == "item" && items.size < MAX_ITEMS) {
                        val id = a.str("id")
                        val href = a.str("href")
                        if (href.isNotEmpty()) {
                            val item = Item(id, href, a.str("media-type"))
                            items.add(item)
                            if (id.isNotEmpty()) byId.putIfAbsent(id, item)
                            byHref.putIfAbsent(normalize(href), item)
                        }
                    }
                    return null
                }
            }
            "spine" -> object : XNode() {
                override fun child(name: String, a: XAttrs): XNode? {
                    if (name == "itemref" && spine.size < MAX_ITEMS) a["idref"]?.let { spine.add(it) }
                    return null
                }
            }
            else -> null
        }
    }

    companion object {
        private const val MAX_ITEMS = 100_000

        /** "./Contents\\section0.xml" → "contents/section0.xml" (대소문자 무시 비교용) */
        fun normalize(path: String): String {
            var p = path.replace('\\', '/').trim()
            while (p.startsWith("./")) p = p.substring(2)
            p = p.trimStart('/')
            return p.lowercase()
        }
    }
}

/** META-INF/manifest.xml: 암호화된 항목이 있는지 */
internal class HwpxEncryptionInfo {
    var encrypted = false
        private set
    val encryptedPaths = HashSet<String>()

    val root: XNode = object : XNode() {
        override fun child(name: String, a: XAttrs): XNode? = if (name == "manifest") ManifestNode() else throw StopParsingException()
    }

    private inner class ManifestNode : XNode() {
        override fun child(name: String, a: XAttrs): XNode? =
            if (name == "file-entry") FileEntryNode(a.str("full-path")) else null
    }

    private inner class FileEntryNode(private val path: String) : XNode() {
        override fun child(name: String, a: XAttrs): XNode? {
            if (name == "encryption-data") {
                encrypted = true
                if (path.isNotEmpty() && encryptedPaths.size < 10_000) encryptedPaths.add(HwpxManifest.normalize(path))
            }
            return null
        }
    }
}
