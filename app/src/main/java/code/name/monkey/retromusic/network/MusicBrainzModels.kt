package code.name.monkey.retromusic.network

import com.google.gson.annotations.SerializedName

data class MBReleaseResponse(
    @SerializedName("releases") val releases: List<MBRelease>
)

data class MBRelease(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("date") val date: String?,
    @SerializedName("artist-credit") val artistCredit: List<MBArtistCredit>?,
    @SerializedName("media") val media: List<MBMedia>?
)

data class MBArtistCredit(
    @SerializedName("name") val name: String
)

data class MBMedia(
    @SerializedName("tracks") val tracks: List<MBTrack>?
)

data class MBTrack(
    @SerializedName("position") val position: Int,
    @SerializedName("title") val title: String,
    @SerializedName("length") val length: Int?
)
