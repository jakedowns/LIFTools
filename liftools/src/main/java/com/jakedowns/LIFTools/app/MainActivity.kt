package com.jakedowns.LIFTools.app

import android.Manifest
import android.R.attr.*
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer
import com.leiainc.androidsdk.core.QuadView
import com.leiainc.androidsdk.core.ScaleType
import com.leiainc.androidsdk.display.LeiaDisplayManager
import com.leiainc.androidsdk.display.LeiaSDK
import com.jakedowns.LIFTools.app.utils.DiskRepository
import com.jakedowns.LIFTools.app.utils.ExtraTypeCoercion.toBoolean
import com.jakedowns.LIFTools.app.utils.ExtraTypeCoercion.toInt
import kotlinx.coroutines.selects.select
import org.apache.commons.io.FilenameUtils
import java.io.File


// Request Codes
const val REQUEST_PICK_IMAGE_FILE = 100
const val REQUEST_PICK_OUTPUT_DIR = 101

class MainActivity : AppCompatActivity(), EventListener {
    val TAG: String = "LIFTools"

    private val mainViewModel by viewModels<MainViewModel>()
    private var preview2DSurface: PreviewSurfaceView? = null

    private var mainUIHidden = false
    private var sawIntroScreen = false

    var displayManager: LeiaDisplayManager? = null
    val IS_LEIA_DEVICE: Boolean
        get() {
            return displayManager != null
        }

    data class BitmapToBake(
        val filename: String,
        val bitmap: Bitmap,
        val mode: MainViewModel.PreviewMode
    )

    var mSelectedFilename: String? = null

    var mOutputUri: Uri? = null;

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super<AppCompatActivity>.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        displayManager = LeiaSDK.getDisplayManager(applicationContext)

        if(!setCheckboxStateFromSavedPrefs()){
            Log.e(TAG,"Error setting checkboxes from saved prefs")
        }

//        val mainControlsFragment: Fragment? = supportFragmentManager.findFragmentById(R.id
//            .fragment_container_view);
        val mainControlsWrapper: View = requireViewById(R.id.fragment_container_view)
        mainControlsWrapper.visibility = View.GONE

        preview2DSurface = findViewById<PreviewSurfaceView>(R.id.preview_2d)
        preview2DSurface?.visibility = View.GONE

        // Requesting Permission to access External Storage
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE),
            23
        )

        val button: Button = findViewById(R.id.action_open_image_picker)
        button.setOnClickListener(View.OnClickListener {
            showFilePicker()
            sawIntroScreen = true // track that we've moved past first "screen" so that on
        // rotation we show the proper ui
        })

        mainViewModel.mainActivity = this
        mainViewModel.preview2DSurface = preview2DSurface;

        /*  Get reference to QuadView */
        val quadView: QuadView = findViewById(R.id.quad_view)

        /*  Set scale type to Fit center  */
        quadView.scaleType = ScaleType.FIT_CENTER

        val quadBitmapObserver = Observer<Bitmap> { quadBitmap ->
            // Observe LiveData to update UI when Quad Bitmap is available.
            if (quadBitmap != null) {
                quadView.setQuadBitmap(quadBitmap)
            } else {
                Toast.makeText(this, "Failed to retrieve Image", Toast.LENGTH_LONG).show()
            }
        }

        mainViewModel.quadBitmapLiveData.observe(this, quadBitmapObserver)

        mainViewModel.parsedFileCountLiveData.observe(this, parsedFileCountObserver)
    }

    /*
        Once this value increments, we know the file is loaded & decoded successfully
     */
    private val parsedFileCountObserver = Observer<Int> { _ ->
        mainViewModel.redrawQuad()
        checkToggle3D()
    }

    fun setCheckboxStateFromSavedPrefs(): Boolean{
        val sharedPref = getSharedPreferences(applicationContext.resources
            .getResourceEntryName(R.string
                .user_prefs_key),Context.MODE_PRIVATE) ?: return false

        val res = applicationContext.resources;
        val prefName4VST = res.getString(R.string.export_opt_cb_4V_ST_key)
        val prefNameDEPTHMAP = res.getString(R.string.export_opt_cb_disparity_maps_key)
        val prefNameSTCV = res.getString(R.string.export_opt_cb_CV_2x1_key)
        val prefNamePREVIEW3D = res.getString(R.string.export_opt_cb_PREVIEW_3D_key)
        val prefNameCurrentFilename = res.getString(R.string.pref_string_state_selected_filename)
//        val prefNameCurrentFilepath = res.getString(R.string.pref_string_state_selected_filepath)

        val EXPORT_4V_ST = sharedPref.getInt(prefName4VST,0).toBoolean()
        val EXPORT_DISPARITY_MAPS = sharedPref.getInt(prefNameDEPTHMAP,0).toBoolean()
        val EXPORT_ST_CROSSVIEW = sharedPref.getInt(prefNameSTCV,0).toBoolean()
        val PREVIEW_3D = sharedPref.getInt(prefNamePREVIEW3D,1).toBoolean()

        Log.i(TAG,"$EXPORT_4V_ST $EXPORT_DISPARITY_MAPS $EXPORT_ST_CROSSVIEW $PREVIEW_3D")

        val fragment = requireViewById<View>(R.id.fragment_container_view)
        fragment.findViewById<CheckBox?>(R.id.cb_4V_ST)?.isChecked = EXPORT_4V_ST
        fragment.findViewById<CheckBox?>(R.id.cb_disparity_maps)?.isChecked = EXPORT_DISPARITY_MAPS
        fragment.findViewById<CheckBox?>(R.id.cb_CV_2x1)?.isChecked = EXPORT_ST_CROSSVIEW
        fragment.findViewById<CheckBox?>(R.id.cb_PREVIEW_3D)?.isChecked = PREVIEW_3D

        return true
    }

    // prompt the user to select an image
    fun showFilePicker() {
        val pickerInitialUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"

//            setType("*/*")
//            val mimetypes = arrayOf("image/*", "video/*")
//            putExtra(Intent.EXTRA_MIME_TYPES, mimetypes)

            // Optionally, specify a URI for the file that should appear in the
            // system file picker when it loads.
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, pickerInitialUri)
        }

        startActivityForResult(intent, REQUEST_PICK_IMAGE_FILE)
    }

    // prompt the user to select where they want us to export files
    // @note: just defaulting to Pictures/LIFToolsExports for now
    // there's some weirdness where we'd (apparently) need to prevent them from choosing Download
    // dir or subdir, and other providers, or make sure we handle all other provider cases
    // i don't fully understand it, so i'm skipping that for now
//    fun showOutputDirectoryPicker() {
//        val pickerInitialUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
//        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
////            addCategory(Intent.CATEGORY_OPENABLE)
////            type = DocumentsContract.Document.MIME_TYPE_DIR;
//            putExtra(DocumentsContract.EXTRA_INITIAL_URI, pickerInitialUri)
//        }
//
//        startActivityForResult(intent, REQUEST_PICK_OUTPUT_DIR)
//    }

    //unused for now
//    fun makeSpinner(){
//        //get the spinner from the xml.
//
//        //get the spinner from the xml.
////            val dropdown = findViewById<Spinner>(R.id.export_type_spinner)
////            //create a list of items for the spinner.
////            val items = arrayOf(
////                "2",
////                "2",
////                "three"
////            )
////            //create an adapter to describe how the items are displayed, adapters are used in several places in android.
////            //There are multiple variations of this, but this is the basic variant.
////            val adapter = ArrayAdapter(this, R.layout.spinner_row, items)
////            //set the spinners adapter to the previously created one.
////            dropdown.adapter = adapter
//    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        when(requestCode){
            REQUEST_PICK_IMAGE_FILE -> {
                val intro_layout: FrameLayout = findViewById(R.id.intro_layout)
                intro_layout.visibility = View.GONE

                val mainControlsWrapper: View? = findViewById(R.id.fragment_container_view)
                mainControlsWrapper?.visibility = View.VISIBLE

                setCheckboxStateFromSavedPrefs()

                mainControlsWrapper?.findViewById<CheckBox>(R.id.cb_PREVIEW_3D)?.visibility =
                    when (IS_LEIA_DEVICE){
                    true -> View.VISIBLE
                    else -> View.GONE
                }


                if (resultCode == Activity.RESULT_OK) {
                    resultData?.data?.also { fileUri ->

                        /**
                         * Upon getting a document uri returned, we can use
                         * [ContentResolver.takePersistableUriPermission] in order to persist the
                         * permission across restarts.
                         *
                         * This may not be necessary for your app. If the permission is not
                         * persisted, access to the uri is granted until the receiving Activity is
                         * finished. You can extend the lifetime of the permission grant by passing
                         * it along to another Android component. This is done by including the uri
                         * in the data field or the ClipData object of the Intent used to launch that
                         * component. Additionally, you need to add FLAG_GRANT_READ_URI_PERMISSION
                         * and/or FLAG_GRANT_WRITE_URI_PERMISSION to the Intent.
                         *
                         * This app takes the persistable URI permission grant to demonstrate how, and
                         * to allow us to reopen the last opened document when the app starts.
                         */
                        contentResolver.takePersistableUriPermission(
                            fileUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )

                        // Get the Uri of the selected file
                        val uri: Uri = fileUri
                        val uriString = uri.toString()
                        val myFile = File(uriString)
                        val path = myFile.absolutePath
                        var displayName: String? = null
                        if (uriString.startsWith("content://")) {
                            var cursor: Cursor? = null
                            try {
                                cursor =
                                    contentResolver.query(uri,
                                        null, null, null,
                                        null)
                                if (cursor != null && cursor.moveToFirst()) {
                                    displayName =
                                        cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                                }
                            } finally {
                                cursor?.close()
                            }
                        } else if (uriString.startsWith("file://")) {
                            displayName = myFile.name
                        }
//                Log.i(TAG,"$path $displayName")

                        val sharedPref = getSharedPreferences(resources.getResourceEntryName(R.string
                            .user_prefs_key),Context
                            .MODE_PRIVATE) ?: return

                        with (sharedPref.edit()) {
                            putString(resources.getString(R.string
                                .pref_string_state_selected_filepath), path)
                            putString(resources.getString(R.string
                                .pref_string_state_selected_filename),
                                displayName)
                            apply()
                        }

                        mSelectedFilename = displayName

                        // TODO offer to re-open recently opened files

                        mainViewModel.openSelectedFile(fileUri)
                    }
                }
            }
            // unused for now
//            REQUEST_PICK_OUTPUT_DIR -> {
//                if (resultCode == Activity.RESULT_OK) {
//                    Toast.makeText(this, "Exporting...", Toast.LENGTH_LONG)
//                        .show()
//                    resultData?.data?.also { fileUri ->
//                        val sharedPref = getSharedPreferences(resources.getResourceEntryName(R.string
//                            .user_prefs_key),Context
//                            .MODE_PRIVATE) ?: return
//
//                        with (sharedPref.edit()) {
//                            putString(resources.getString(R.string
//                                .pref_string_export_path), fileUri.path)
//                            apply()
//                        }
//
//                        Log.i(TAG, "user selected output dir "+fileUri.path)
//
//                        mOutputUri = fileUri
//                        finalizeExportAfterDirectorySelected()
//                    }
//                }else{
//                    Toast.makeText(this, "Error selecting output directory. Try again or contact " +
//                            "@jakedowns", Toast.LENGTH_LONG)
//                        .show()
//                }
//            }
            else -> {}
        }

    }

    private fun getStringResourceByName(context: Context,  resourceName: String): String? {
        try {
            val packageName = packageName
            val resId = context.resources.getIdentifier(resourceName, "string", packageName)
//            Log.i("Resource", "Resource Id: $resId")
            return getString(resId)
        }catch (ex : Exception){
            Log.e("Error", ex.message.toString())
        }
        return null
    }

    override fun onCheckboxClicked(view: View) {
        val id = resources.getResourceEntryName(view.id)
        val cb: CheckBox = view as CheckBox;
        val enabled = cb.isChecked().toInt()
        val keyname = getStringResourceByName(applicationContext, "export_opt_"+id+"_key")
        val sharedPref = getSharedPreferences(resources.getResourceEntryName(R.string
            .user_prefs_key),Context
            .MODE_PRIVATE) ?: return

        with (sharedPref.edit()) {
            putInt(keyname,enabled)
            apply()
            mainViewModel.redrawQuad()
            if(id == "cb_PREVIEW_3D"){
                checkToggle3D()
            }
        }
    }

    fun checkToggle3D(){
        val cpm = mainViewModel.currentPreviewMode?:MainViewModel.PreviewMode.MODE_2V
        val sharedPref = getSharedPreferences(resources.getResourceEntryName(R.string
            .user_prefs_key),Context
            .MODE_PRIVATE) ?: return
        val prefName = resources.getString(R.string.export_opt_cb_PREVIEW_3D_key)
        val enable3d = sharedPref.getInt(prefName,1).toBoolean()
        if(
            enable3d
        ){
            if(
                cpm == MainViewModel.PreviewMode.MODE_4V_ST
                || cpm == MainViewModel.PreviewMode.MODE_4V
            ){
                preview2DSurface?.visibility = View.GONE
                requireViewById<QuadView>(R.id.quad_view).visibility = View.VISIBLE
            }
//                Toast.makeText(this, "requesting 3d mode", Toast.LENGTH_LONG).show()
            displayManager?.requestBacklightMode(LeiaDisplayManager.BacklightMode.MODE_3D)
        }else{
            preview2DSurface?.visibility = View.VISIBLE
            requireViewById<QuadView>(R.id.quad_view).visibility = View.GONE
//                Toast.makeText(this, "requesting 2d mode", Toast.LENGTH_LONG).show()
            displayManager?.requestBacklightMode(LeiaDisplayManager.BacklightMode.MODE_2D)
        }
    }

    override fun onPickNewClicked(view: View) {
        showFilePicker()
        Log.i(TAG,"todo show file picker")
    }

    override fun onExportClicked(view: View) {
        // Log.i(TAG, "TODO: export")

        // TODO: prompt user with a dialog stating the previously selected output dir (default to
        //  Photos, or same dir as input?)
        // with the options to: select output dir, photos, same as image

        // test:
        //showOutputDirectoryPicker()

        // skip user specified dir: and always send it to OUR dir
        finalizeExportAfterDirectorySelected()
    }

    fun finalizeExportAfterDirectorySelected() {
//        if(mOutputUri == null){
//            return;
//        }

        // build array of selected modes
        // loop and generate bitmaps
        // save bitmaps to storage
        val ms = System.currentTimeMillis();

        // array of Ints
//        var selectedModesArray = ExportOptionsModel.getSelectedExportOptionsArray(application)
        var selectedOptions = ExportOptionsModel.getSelectedExportOptionsObjectForApp(application)

        val filename_base = FilenameUtils.removeExtension(mSelectedFilename ?: "") + "_$ms"

//        for(i in selectedModesArray) {
//            if(selectedModesArray[i] == 1){
//                val suffix = when(i) {
//                    0 -> "2x1"
//                    1 -> "2x1"
//                    2 -> "2x2"
//                    3 -> "2x2"
//                    else -> "2x1"
//                }
//            }
//        }

        // todo: progress bar
        var numExporting = 0

        // instead of getting fancy, let's hard code, then refactor later
        for(i in 0..3) {
            var filename = filename_base
            var mode = MainViewModel.PreviewMode.MODE_2V
            when(i){
                0 -> if(selectedOptions.EXPORT_ST) {
                    filename += "_ST_2x1.png"
                } else { continue }
                1 -> if(selectedOptions.EXPORT_ST_CROSSVIEW) {
                    filename += "_ST_CROSSVIEW_2x1.png"
                    mode = MainViewModel.PreviewMode.MODE_ST_CROSSVIEW
                } else { continue }
                2 -> if(selectedOptions.EXPORT_4V) {
                    filename += "_4V_2x2.png"
                    mode = MainViewModel.PreviewMode.MODE_4V
                } else { continue }
                3 -> if(selectedOptions.EXPORT_4V_ST) {
                    filename += "_4V_ST_2x2.png"
                    mode = MainViewModel.PreviewMode.MODE_4V_ST
                } else { continue }
                else -> continue
            }

            val outputBitmap = mainViewModel.generateBitmap("ALBEDO",mode)
            saveBitmap(outputBitmap,filename)

            // todo reflow so it's easier to tag albedo v. depth
//            if(selectedOptions.EXPORT_DISPARITY_MAPS){
//                val outputBitmap2 = mainViewModel.generateBitmap("DISPARITY",mode)
//                saveBitmap(outputBitmap2,filename)
//            }
        }
    }

    fun saveBitmap(outputBitmap: Bitmap?, filename: String) {
        if(outputBitmap == null){
            Log.e(TAG,"error exporting $filename")
        }else{
            //TODO: accept options for: mimetype,
            // pass thru old metadata
            // expose options for jpg vs png and quality setting
            try {
                val finalOutputUri = DiskRepository().saveBitmap(
                    filename,
                    outputBitmap,
                    this
                )
                Toast.makeText(this, "Image Saved: ${finalOutputUri?.path}!", Toast.LENGTH_LONG)
                    .show()
            }catch(ex: Exception){
                Toast.makeText(this, "Error Exporting", Toast
                    .LENGTH_LONG)
                    .show()
                Log.e(TAG, ex.message, ex)
            }
        }
    }

    fun onTapSurface(view: View) {
        if(!sawIntroScreen){
            return
        }
        mainUIHidden = !mainUIHidden
        val visNext = if(mainUIHidden){
            View.GONE
        }else{
            View.VISIBLE
        }
        requireViewById<View>(R.id.image_info_text).visibility = visNext
        requireViewById<View>(R.id.fragment_container_view).visibility = visNext
    }

    override fun onPause() {
        super.onPause()
        displayManager = LeiaSDK.getDisplayManager(applicationContext)
        displayManager?.requestBacklightMode(LeiaDisplayManager.BacklightMode.MODE_2D)
    }

    override fun onResume() {
        super.onResume()
        //displayManager = LeiaSDK.getDisplayManager(applicationContext)
//        displayManager?.requestBacklightMode(LeiaDisplayManager.BacklightMode.MODE_3D)

        /*  Make app full screen */
        setFullScreenImmersive()
    }

    private fun setFullScreenImmersive() {
        val flags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        val decorView = window.decorView
        decorView.clearFocus()
        decorView.systemUiVisibility = flags

        // Code below is to handle presses of Volume up or Volume down.
        // Without this, after pressing volume buttons, the navigation bar will
        // show up and won't hide
        decorView.setOnSystemUiVisibilityChangeListener { visibility: Int ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                decorView.systemUiVisibility = flags
            }
        }
    }
}
