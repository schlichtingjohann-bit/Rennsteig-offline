package de.rennsteig.offline

import android.app.Activity
import android.view.View

/** findViewById mit Nicht-null-Garantie (die IDs stammen aus den eigenen Layouts). */
fun <T : View> Activity.view(id: Int): T = findViewById<T>(id)!!

fun <T : View> View.view(id: Int): T = findViewById<T>(id)!!
