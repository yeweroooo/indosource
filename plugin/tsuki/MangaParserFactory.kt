package tsuki

import tsuki.core.MangaParserWrapper
import tsuki.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.id.AinzScansParser
import org.koitharu.kotatsu.parsers.site.id.ApKomikParser
import org.koitharu.kotatsu.parsers.site.id.CosmicToonsParser
import org.koitharu.kotatsu.parsers.site.id.IkiruParser
import org.koitharu.kotatsu.parsers.site.id.KiryuuParser
import org.koitharu.kotatsu.parsers.site.id.LumosKomikParser
import org.koitharu.kotatsu.parsers.site.id.KomiknesiaParser
import org.koitharu.kotatsu.parsers.site.id.MGKomikParser
import org.koitharu.kotatsu.parsers.site.id.ShinigamiParser
import org.koitharu.kotatsu.parsers.site.id.VoratoonParser
import org.koitharu.kotatsu.parsers.site.id.WestmangaParser

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