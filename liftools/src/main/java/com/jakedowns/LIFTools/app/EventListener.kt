package com.jakedowns.LIFTools.app

import android.view.View

interface EventListener {
    fun onCheckboxClicked(view: View)
    fun onExportClicked(view: View)
    fun onPickNewClicked(view: View)
}