package code.name.monkey.retromusic.network

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface MusicBrainzService {
    @GET("release")
    suspend fun searchRelease(
        @Query("query") query: String,
        @Query("fmt") format: String = "json",
        @Header("User-Agent") userAgent: String = "METROX/1.0 (exodust@github.com)"
    ): MBReleaseResponse

    @GET("release/{mbid}")
    suspend fun getReleaseDetails(
        @retrofit2.http.Path("mbid") mbid: String,
        @Query("inc") include: String = "recordings+artist-credits+labels",
        @Query("fmt") format: String = "json",
        @Header("User-Agent") userAgent: String = "METROX/1.0 (exodust@github.com)"
    ): MBRelease
}
