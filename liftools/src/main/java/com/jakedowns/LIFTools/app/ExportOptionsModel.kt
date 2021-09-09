package com.jakedowns.LIFTools.app

import com.jakedowns.LIFTools.app.utils.ExtraTypeCoercion.toBoolean
import android.app.Application
import android.content.Context
import com.jakedowns.LIFTools.app.utils.ExtraTypeCoercion.toInt

data class ExportOptionsObject(
    val EXPORT_ST: Boolean,
    val EXPORT_ST_CROSSVIEW: Boolean,
    val EXPORT_4V: Boolean,
    val EXPORT_4V_ST: Boolean,
    val EXPORT_DISPARITY_MAPS: Boolean,
)

class ExportOptionsModel {
    companion object {
        fun getSelectedExportOptionsObjectForApp(app: Application):
                ExportOptionsObject
        {
            val res = app.applicationContext.resources;
            val userPrefKeyName = res.getResourceEntryName(R.string.user_prefs_key)
            val sharedPref = app.getSharedPreferences(
                userPrefKeyName,
                Context.MODE_PRIVATE)

            val prefNameST = res.getString(R.string.export_opt_cb_ST_2x1_key)
            val prefNameSTCV = res.getString(R.string.export_opt_cb_CV_2x1_key)
            val prefName4V = res.getString(R.string.export_opt_cb_4V_AI_key)
            val prefName4VST = res.getString(R.string.export_opt_cb_4V_ST_key)
            val prefNameDEPTHMAP = res.getString(R.string.export_opt_cb_disparity_maps_key)

            val exportST = sharedPref.getInt(prefNameST, 0).toBoolean()
            val exportSTCrossView = sharedPref.getInt(prefNameSTCV, 0).toBoolean()
            val export4V = sharedPref.getInt(prefName4V, 0).toBoolean()
            val export4VST = sharedPref.getInt(prefName4VST, 0).toBoolean()
            val exportDisparityMaps = sharedPref.getInt(prefNameDEPTHMAP, 0).toBoolean()

            return ExportOptionsObject(
                exportST,
                exportSTCrossView,
                export4V,
                export4VST,
                exportDisparityMaps
            )
        }
        fun getSelectedExportOptionsArray(app: Application): Array<Int> {
            val eoo = getSelectedExportOptionsObjectForApp(app)
            return arrayOf(
                // 0
                eoo.EXPORT_ST.toInt(),
                // 1
                eoo.EXPORT_ST_CROSSVIEW.toInt(),
                // 2
                eoo.EXPORT_4V.toInt(),
                // 3
                eoo.EXPORT_4V_ST.toInt(),
                // 4
                eoo.EXPORT_DISPARITY_MAPS.toInt()
            )
        }
    }
}