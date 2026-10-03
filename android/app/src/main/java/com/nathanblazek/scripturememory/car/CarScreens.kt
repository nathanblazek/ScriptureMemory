package com.nathanblazek.scripturememory.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.nathanblazek.scripturememory.data.AppDataHolder

/** Picks a collection to practice. */
class CollectionsCarScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val collections = AppDataHolder.get(carContext).data.collections.filter { it.passages.isNotEmpty() }
        val list = ItemList.Builder().setNoItemsMessage("Add passages in the app on your phone first.")
        for (c in collections) {
            list.addItem(
                Row.Builder()
                    .setTitle(c.name)
                    .addText("${c.passages.size} passage${if (c.passages.size == 1) "" else "s"}")
                    .setOnClickListener { screenManager.push(PassagesCarScreen(carContext, c.id)) }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle("Scripture Memory")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .build()
    }
}

/** Picks a passage in a collection. */
class PassagesCarScreen(carContext: CarContext, private val collectionId: String) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val collection = AppDataHolder.get(carContext).data.collections.firstOrNull { it.id == collectionId }
        val list = ItemList.Builder().setNoItemsMessage("This collection has no passages.")
        for (p in collection?.passages.orEmpty()) {
            list.addItem(
                Row.Builder()
                    .setTitle(p.reference)
                    .addText(if (p.mastered) "Mastered · ${p.status}" else p.status)
                    .setOnClickListener { screenManager.push(DrivePracticeScreen(carContext, collectionId, p)) }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle(collection?.name ?: "Passages")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
