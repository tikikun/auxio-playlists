/*
 * Copyright (c) 2025 Auxio Project
 * ExternalPlaylists.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.musikr.playlist

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.core.database.getStringOrNull
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.path.MediaStorePathInterpreter
import org.oxycblt.musikr.fs.path.VolumeManager
import org.oxycblt.musikr.fs.saf.contentResolverSafe
import org.oxycblt.musikr.fs.saf.useQuery
import org.oxycblt.musikr.playlist.m3u.M3U

/**
 * Reads playlists that already exist in the system MediaStore (i.e. M3U/PLS/etc. files that were
 * created by other apps or copied onto the device) so that they show up in the library without
 * having to be imported one-by-one.
 *
 * The MediaStore playlists table is used as the index of playlist files, mirroring how players
 * such as Gramophone discover them, and the playlist file contents are then parsed with the same
 * [M3U] implementation that the import feature uses.
 */
internal interface ExternalPlaylists {
    /**
     * Scan for playlist files that are known to the [MediaStore] and resolve their songs.
     *
     * @param library The [Library] to resolve the songs of each playlist against.
     * @return A list of playlist names paired with their resolved songs. Playlists that could not
     *   be resolved, are empty, or already exist in [library] are omitted.
     */
    suspend fun scan(library: Library): List<Pair<String, List<Song>>>

    companion object {
        fun from(context: Context): ExternalPlaylists =
            ExternalPlaylistsImpl(context, VolumeManager.from(context), M3U.from(context))
    }
}

private class ExternalPlaylistsImpl(
    private val context: Context,
    private val volumeManager: VolumeManager,
    private val m3u: M3U,
) : ExternalPlaylists {
    private val pathInterpreterFactory = MediaStorePathInterpreter.Factory.from(volumeManager)

    override suspend fun scan(library: Library): List<Pair<String, List<Song>>> {
        val start = System.currentTimeMillis()
        val out = mutableListOf<Pair<String, List<Song>>>()
        val seen = mutableSetOf<String>()

        try {
            context.contentResolverSafe.useQuery(
                @Suppress("DEPRECATION") MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    @Suppress("DEPRECATION") MediaStore.Audio.Playlists.NAME
                ) + pathInterpreterFactory.projection,
                null,
                null,
            ) { cursor ->
                val pathInterpreter = pathInterpreterFactory.wrap(cursor)
                val idIndex =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex =
                    cursor.getColumnIndexOrThrow(
                        @Suppress("DEPRECATION") MediaStore.Audio.Playlists.NAME)

                while (cursor.moveToNext()) {
                    val path = pathInterpreter.extract()
                    if (path == null) {
                        Log.w(TAG, "Could not interpret the path of a MediaStore playlist")
                        continue
                    }

                    val name =
                        cursor.getStringOrNull(nameIndex)?.takeIf { it.isNotBlank() }
                            ?: path.name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                            ?: continue

                    // Already in the database (either imported or created by the user)
                    if (library.findPlaylistByName(name) != null || !seen.add(name)) {
                        continue
                    }

                    val id = cursor.getLong(idIndex)
                    val uri =
                        ContentUris.withAppendedId(
                            @Suppress("DEPRECATION")
                            MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                            id)

                    val imported =
                        try {
                            context.contentResolverSafe.openInputStream(uri)?.use { stream ->
                                m3u.read(stream, path.directory)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not read playlist file $path", e)
                            null
                        }
                    if (imported == null) {
                        Log.w(TAG, "Could not parse playlist file $path")
                        continue
                    }

                    val songs =
                        imported.paths.mapNotNull {
                            it.firstNotNullOfOrNull(library::findSongByPath)
                        }
                    if (songs.isEmpty()) {
                        Log.w(TAG, "No songs of playlist $path could be resolved")
                        continue
                    }

                    Log.d(TAG, "Adopting playlist $name with ${songs.size} songs")
                    out.add(name to songs)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query MediaStore playlists", e)
        }

        Log.d(
            TAG,
            "Scanned MediaStore playlists in ${System.currentTimeMillis() - start}ms, " +
                "${out.size} new playlist(s)")
        return out
    }

    private companion object {
        const val TAG = "ExternalPlaylists"
    }
}
