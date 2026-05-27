package com.bjorntech.player

import android.content.Context
import androidx.core.content.edit

object FavouritesManager {

    private const val PREFS = "favourites"
    private const val KEY = "ids"

    private fun ids(context: Context): MutableSet<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())!!.toMutableSet()

    private fun save(context: Context, set: Set<String>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putStringSet(KEY, set) }

    fun getFavourites(context: Context): Set<Long> = ids(context).mapNotNull { it.toLongOrNull() }.toSet()

    fun isFavourite(context: Context, id: Long) = ids(context).contains(id.toString())

    fun toggle(context: Context, id: Long): Boolean {
        val set = ids(context)
        val key = id.toString()
        if (set.contains(key)) set.remove(key) else set.add(key)
        save(context, set)
        return set.contains(key)
    }
}
