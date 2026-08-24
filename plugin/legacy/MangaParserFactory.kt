package org.koitharu.kotatsu.parsers

import org.koitharu.kotatsu.parsers.core.MangaParserWrapper
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.id.legacy.AinzScansParser
import org.koitharu.kotatsu.parsers.site.id.legacy.ApKomikParser
import org.koitharu.kotatsu.parsers.site.id.legacy.CosmicToonsParser
import org.koitharu.kotatsu.parsers.site.id.legacy.IkiruParser
import org.koitharu.kotatsu.parsers.site.id.legacy.KiryuuParser
import org.koitharu.kotatsu.parsers.site.id.legacy.LumosKomikParser
import org.koitharu.kotatsu.parsers.site.id.legacy.KomiknesiaParser
import org.koitharu.kotatsu.parsers.site.id.legacy.MGKomikParser
import org.koitharu.kotatsu.parsers.site.id.legacy.ShinigamiParser
import org.koitharu.kotatsu.parsers.site.id.legacy.VoratoonParser
import org.koitharu.kotatsu.parsers.site.id.legacy.WestmangaParser

public fun newParser(source: MangaParserSource, context: MangaLoaderContext): MangaParser {
    val parser: MangaParser = when (source) {
        MangaParserSource.MGKOMIK -> MGKomikParser(context)
        MangaParserSource.SHINIGAMI -> ShinigamiParser(context)
        MangaParserSource.KIRYUU -> KiryuuParser(context)
        MangaParserSource.APKOMIK -> ApKomikParser(context)
        MangaParserSource.VORATOON -> VoratoonParser(context)
        MangaParserSource.IKIRU -> IkiruParser(context)
        MangaParserSource.COSMICTOONS -> CosmicToonsParser(context)
        MangaParserSource.KOMIKNESIA -> KomiknesiaParser(context)
        MangaParserSource.WESTMANGA -> WestmangaParser(context)
        MangaParserSource.AINZSCANS -> AinzScansParser(context)
        MangaParserSource.LUMOSKOMIK -> LumosKomikParser(context)
    }
    check(parser.source == source) {
        "Cannot instantiate manga parser: ${source.name} mapped to ${parser.source}"
    }
    return MangaParserWrapper(parser)
}