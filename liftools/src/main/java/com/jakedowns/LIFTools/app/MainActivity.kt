package com.jakedowns.LIFTools.app

import android.Manifest
import android.R.attr.*
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.apache.commons.io.FilenameUtils
import java.io.File
import com.jakedowns.LIFTools.app.MainViewModel.MapType


// Request Codes
const val REQUEST_PICK_IMAGE_FILE = 100
const val REQUEST_PICK_OUTPUT_DIR = 101

class MainActivity : AsyncActivity(), EventListener {
    val TAG: String = "LIFTools"

    private val mainViewModel by viewModels<MainViewModel>()
    private var preview2DSurface: PreviewSurfaceView? = null
    var quadView: QuadView? = null

    var mainUIHidden = false
    var viewingIntro = true
    var mViewingDontateModal = false
    var numExportsPending = 0
    var mWaitingForFirstRenderAfterImport = false;

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
        outState.putBoolean("viewingIntro",viewingIntro);
        outState.putBoolean("mWaitingForFirstRenderAfterImport",mWaitingForFirstRenderAfterImport);
        outState.putBoolean("mViewingDontateModal",mViewingDontateModal);
        outState.putBoolean("mainUIHidden",mainUIHidden);

        // NOTE: Export will be interrupted on rotate
        // TODO: store how many, and which exactly exports we have in the queue,
        // re-queue them on restore
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        viewingIntro = savedInstanceState.getBoolean("viewingIntro")
        mViewingDontateModal = savedInstanceState.getBoolean("mViewingDontateModal")
        mWaitingForFirstRenderAfterImport = savedInstanceState.getBoolean("mWaitingForFirstRenderAfterImport")
        mainUIHidden = savedInstanceState.getBoolean("mainUIHidden")

        updateUIViz();
    }

    override fun onBackPressed() {
        if(mViewingDontateModal){
            // back to intro
            toggleDontateModalVisibility(false)
        }else if(!viewingIntro){
            returnToIntroView()
        }
    }

    fun updateUIViz() {
        updateUIViz(null);
    }

    fun updateUIViz(newConfig: Configuration?) {
        val config = newConfig ?: resources.configuration;
        //
        val mainControlsWrapper: View = findViewById(R.id.fragment_container_view)
        var mainControlsVizNext = View.GONE
        //
        val buttonWrapper: LinearLayout? = mainControlsWrapper.findViewById(R.id.button_wrapper)
        var buttonWrapperVizNext = View.GONE
        //
        val introLayout: FrameLayout = findViewById(R.id.intro_layout)
        var introLayoutVizNext = View.GONE
        // spinner vis
        val circleSpinnerWrapper = findViewById<View?>(R.id.progress_spinner_wrapper)
        val spinnerVizNext = if(mWaitingForFirstRenderAfterImport){
            View.VISIBLE
        }else{
            View.GONE
        }
        circleSpinnerWrapper?.visibility = spinnerVizNext

        // preview surface vis
        val preview2DSurface = findViewById<PreviewSurfaceView>(R.id.preview_2d)
        var preivew2DVizNext = View.GONE

        // loading bar vis
        val loadingBar = findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progress_bar)
        var loadingBarVizNext = View.GONE

        if(viewingIntro) {
            introLayoutVizNext = View.VISIBLE
        }else if(mViewingDontateModal){
            // display "donate" ui
            toggleDontateModalVisibility(mViewingDontateModal)
        }else if(mWaitingForFirstRenderAfterImport) {
            // display "loading" ui
        }else if(mainViewModel.exportsRemainingCountLiveData.value?:0 > 0) {
            // display "exporting" ui
            loadingBarVizNext = View.VISIBLE
        }else{
            // assume in "main" ui
            mainControlsVizNext = if(!mainUIHidden){
                View.VISIBLE
            }else{
                View.GONE
            }
            buttonWrapperVizNext = View.VISIBLE
        }

        // Toggle visibility
        mainViewModel.redrawQuad()
        introLayout.visibility = introLayoutVizNext
        mainControlsWrapper.visibility = mainControlsVizNext
        preview2DSurface?.visibility = preivew2DVizNext
        loadingBar?.visibility = loadingBarVizNext
        buttonWrapper?.visibility = buttonWrapperVizNext
        val b1 = buttonWrapper?.findViewById<Button>(R.id.action_pick_new_image)
        val b2 = buttonWrapper?.findViewById<Button>(R.id.action_export_images)
        // check orientation of button wrapper
        val lp1 = b1?.getLayoutParams()
        val lp2 = b2?.getLayoutParams()
        if(config.orientation == Configuration.ORIENTATION_LANDSCAPE){
            buttonWrapper?.orientation = LinearLayout.HORIZONTAL

            lp1?.width = 0
            lp1?.height = LinearLayout.LayoutParams.WRAP_CONTENT

            lp2?.width = 0
            lp2?.height = LinearLayout.LayoutParams.WRAP_CONTENT
        }else{
            buttonWrapper?.orientation = LinearLayout.VERTICAL

            lp1?.width = LinearLayout.LayoutParams.MATCH_PARENT
            lp1?.height = LinearLayout.LayoutParams.WRAP_CONTENT

            lp2?.width = LinearLayout.LayoutParams.MATCH_PARENT
            lp2?.height = LinearLayout.LayoutParams.WRAP_CONTENT
        }
        b1?.layoutParams = lp1
        b2?.layoutParams = lp2

        // TODO: we could make this smarter and pipe only 2 of the views into the network
        // to get disparity back out, but for now, just hide the option when 4 views
        contextualizeDepthMapOption()
    }

    fun contextualizeDepthMapOption() {
        val mainControlsWrapper: View = findViewById(R.id.fragment_container_view)
        val cbDisparityMaps = mainControlsWrapper.findViewById<CheckBox?>(R.id.cb_disparity_maps)
        val depthMapOptionVizNext = if(mainViewModel.multiviewImage?.viewPoints?.size == 4){
            View.GONE
        }else{
            View.VISIBLE
        }
        cbDisparityMaps?.visibility = depthMapOptionVizNext;

        // disable cbDisparityMaps in prefs
        if(depthMapOptionVizNext == View.GONE){
            val sharedPref = getSharedPreferences(resources.getResourceEntryName(R.string
                .user_prefs_key),Context
                .MODE_PRIVATE)
            with (sharedPref?.edit()) {
                val prefNameDEPTHMAP = resources.getString(R.string.export_opt_cb_disparity_maps_key)
                this?.putInt(prefNameDEPTHMAP, 0)
                this?.apply()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super<AsyncActivity>.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        displayManager = LeiaSDK.getDisplayManager(applicationContext)

        if(!setCheckboxStateFromSavedPrefs()){
            Log.e(TAG,"Error setting checkboxes from saved prefs")
        }

//        val mainControlsFragment: Fragment? = supportFragmentManager.findFragmentById(R.id
//            .fragment_container_view);
        val mainControlsWrapper: View = findViewById(R.id.fragment_container_view)
//        val buttonWrapper: LinearLayout? = mainControlsWrapper.findViewById(R.id.button_wrapper)

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
        })

        mainViewModel.mainActivity = this
        mainViewModel.preview2DSurface = preview2DSurface;

        /*  Get reference to QuadView */
        quadView = findViewById<QuadView>(R.id.quad_view)

        /*  Set scale type to Fit center  */
        quadView?.scaleType = ScaleType.FIT_CENTER

        val quadBitmapObserver = Observer<Bitmap> { quadBitmap ->
            // Observe LiveData to update UI when Quad Bitmap is available.
            if (quadBitmap != null) {
                quadView?.setQuadBitmap(quadBitmap)
            } else {
                Toast.makeText(this, "Failed to retrieve Image", Toast.LENGTH_LONG).show()
            }
        }

        mainViewModel.quadBitmapLiveData.observe(this, quadBitmapObserver)

        mainViewModel.parsedFileCountLiveData.observe(this, parsedFileCountObserver)

        mainViewModel.exportsRemainingCountLiveData.observe(this, exportProgressObserver)

        findViewById<View?>(R.id.app_description)?.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://twitter.com/jakedowns"))
            startActivity(browserIntent)
        }

        updateUIViz()
    }

    private val exportProgressObserver = Observer<Int> {
        val bar = findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progress_bar)
        val textView = findViewById<com.leiainc.androidsdk.core.AntialiasingTextView>(R.id.export_text)
        if(it==-1){
            numExportsPending = 0
            // hide progress bar and show controls again
            val mainControlsWrapper: View? = findViewById(R.id.fragment_container_view)
            mainControlsWrapper?.visibility = View.VISIBLE
            bar?.visibility = View.GONE
            textView?.visibility = View.GONE
//            quadView?.visibility = View.VISIBLE
//            preview2DSurface?.visibility = View.VISIBLE
            mainViewModel.redrawQuad()
        }else if(numExportsPending > 0){
            bar?.progress = numExportsPending - it
            textView?.visibility = View.VISIBLE
            textView?.text = "exporting (${numExportsPending - it + 1}/$numExportsPending)\n(this may take a minute. don't rotate device or leave app)"
        }
    }

    /*
        Once this value increments, we know the file is loaded & decoded successfully
     */
    private val parsedFileCountObserver = Observer<Int> { _ ->
        if(mainViewModel.multiviewImage?.viewPoints?.size === 1){
            Toast.makeText(this, "", Toast.LENGTH_LONG).show()
        }
        mainViewModel.redrawQuad()
        checkToggle3D(true)
        mWaitingForFirstRenderAfterImport = false
        updateUIViz()
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

        val export4VST = sharedPref.getInt(prefName4VST,0).toBoolean()
        val exportDisparityMaps = sharedPref.getInt(prefNameDEPTHMAP,0).toBoolean()
        val exportSTCV = sharedPref.getInt(prefNameSTCV,0).toBoolean()
        val preview3D = sharedPref.getInt(prefNamePREVIEW3D,1).toBoolean()

        Log.i(TAG,"$export4VST $exportDisparityMaps $exportSTCV $preview3D")

        val fragment = findViewById<View>(R.id.fragment_container_view)
        if(fragment != null) {
            fragment.findViewById<CheckBox>(R.id.cb_4V_ST)?.isChecked = export4VST
            fragment.findViewById<CheckBox>(R.id.cb_disparity_maps)?.isChecked = exportDisparityMaps
            fragment.findViewById<CheckBox>(R.id.cb_CV_2x1)?.isChecked = exportSTCV
            fragment.findViewById<CheckBox>(R.id.cb_PREVIEW_3D)?.isChecked = preview3D
        }

        return true
    }

    // prompt the user to select an image
    fun showFilePicker() {
        val circleSpinnerWrapper = findViewById<View?>(R.id.progress_spinner_wrapper)
        circleSpinnerWrapper?.visibility = View.VISIBLE

        val preview2DSurface = findViewById<PreviewSurfaceView>(R.id.preview_2d)
        preview2DSurface?.visibility = View.GONE

        quadView?.visibility = View.GONE

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

        // Original Sync Version:
        //startActivityForResult(intent, REQUEST_PICK_IMAGE_FILE)
        // NEW Async Version:
        GlobalScope.launch(Dispatchers.Main) {
            val result = launchIntent(intent).await()
            result?.data?.let {
                val introLayout: FrameLayout = findViewById(R.id.intro_layout)
                val mainControlsWrapper: View? = findViewById(R.id.fragment_container_view)

                setCheckboxStateFromSavedPrefs()

                mainControlsWrapper?.findViewById<CheckBox>(R.id.cb_PREVIEW_3D)?.visibility =
                    when (IS_LEIA_DEVICE){
                        true -> View.VISIBLE
                        else -> View.GONE
                    }

                if (result.resultCode == Activity.RESULT_OK) {
                    viewingIntro = false
                    mWaitingForFirstRenderAfterImport = true
                    runOnUiThread {
                        introLayout.visibility = View.GONE
                        circleSpinnerWrapper?.visibility = View.VISIBLE
                    }
                    it.data?.also { fileUri ->

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
                            .MODE_PRIVATE) ?: return@also

                        with (sharedPref.edit()) {
                            putString(resources.getString(R.string
                                .pref_string_state_selected_filepath), path)
                            putString(resources.getString(R.string
                                .pref_string_state_selected_filename),
                                displayName)
                            apply()
                        }

                        mSelectedFilename = displayName

                        mainViewModel.openSelectedFile(fileUri)
                    }
                }else{
                    onErrorLoadingFromFile()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if(hasFocus) {
            checkToggle3D(true)
        }else{
            checkToggle3D(false)
        }
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
//            REQUEST_PICK_IMAGE_FILE -> {
//
//            }
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

    fun onErrorLoadingFromFile(){
        mWaitingForFirstRenderAfterImport = false;
        // return to "intro" screen
        returnToIntroView()
        Toast.makeText(this, "Error opening selected file.", Toast.LENGTH_LONG).show()
    }

    fun returnToIntroView(){
        viewingIntro = true
        val introLayout: FrameLayout = findViewById(R.id.intro_layout)
        val mainControlsWrapper: View? = findViewById(R.id.fragment_container_view)
        val bar = findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progress_bar)
        bar?.visibility = View.GONE
        findViewById<View?>(R.id.progress_spinner_wrapper)?.visibility = View.GONE

        preview2DSurface?.visibility = View.GONE
        quadView?.visibility = View.GONE

        mainControlsWrapper?.visibility = View.GONE

        introLayout.visibility = View.VISIBLE
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

    override fun onConfigurationChanged(newConfig: Configuration){
        super.onConfigurationChanged(newConfig)

        updateUIViz(newConfig)
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
                checkToggle3D(true)
            }
        }
    }

    fun checkToggle3D(desiredState: Boolean) {
        val cpm = mainViewModel.currentPreviewMode?:MainViewModel.PreviewMode.MODE_2V
        val sharedPref = getSharedPreferences(resources.getResourceEntryName(R.string
            .user_prefs_key),Context
            .MODE_PRIVATE) ?: return
        val prefName = resources.getString(R.string.export_opt_cb_PREVIEW_3D_key)
        val enable3d = !viewingIntro && desiredState && sharedPref.getInt(prefName,1).toBoolean()
        if(
            enable3d
        ){
            if(
                cpm == MainViewModel.PreviewMode.MODE_4V_ST
                || cpm == MainViewModel.PreviewMode.MODE_4V
            ){
                preview2DSurface?.visibility = View.GONE
                findViewById<QuadView>(R.id.quad_view).visibility = View.VISIBLE
            }
//                Toast.makeText(this, "requesting 3d mode", Toast.LENGTH_LONG).show()
            displayManager?.requestBacklightMode(LeiaDisplayManager.BacklightMode.MODE_3D)
        }else{
            preview2DSurface?.visibility = View.VISIBLE
            findViewById<QuadView>(R.id.quad_view).visibility = View.GONE
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

    fun onTapDonate(view: View){
        toggleDontateModalVisibility(true);
    }
    fun onTapDonateHide(view: View){
        toggleDontateModalVisibility(false);
    }

    fun toggleDontateModalVisibility(visible: Boolean) {
        mViewingDontateModal = visible;
        findViewById<View?>(R.id.donate_modal_fragment)?.visibility = when(visible) {
            true -> View.VISIBLE
            false -> View.GONE
        }
        if(visible){
            val donateModalFragment = findViewById<View?>(R.id.donate_modal_fragment)
            val donateTable = donateModalFragment?.findViewById<TableLayout>(R.id.donate_table)
            val venmoRow = donateTable?.findViewById<TableRow?>(R.id.venmo_row)
            venmoRow?.setOnClickListener {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://venmo.com/jacobdowns3"))
                startActivity(browserIntent)
            }
            val paypalRow = donateTable?.findViewById<TableRow?>(R.id.paypal_row)
            paypalRow?.setOnClickListener {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/fallaciousimpala"))
                startActivity(browserIntent)
            }
        }
    }

    fun finalizeExportAfterDirectorySelected() {
//        if(mOutputUri == null){
//            return;
//        }

        // build array of selected modes
        // loop and generate bitmaps
        // save bitmaps to storage
        val ms = System.currentTimeMillis();
        val selectedOptions = ExportOptionsModel.getSelectedExportOptionsObjectForApp(application)

        val filename_base = FilenameUtils.removeExtension(mSelectedFilename ?: "") + "_$ms"

        numExportsPending = 0
        val exportModeList = ArrayList<Triple<MainViewModel.PreviewMode,MainViewModel.MapType,String>>()

        // instead of getting fancy, let's hard code, then refactor later
        for(i in 0..3) {
            var filename = filename_base.replace("_2x2","").replace("_2x1","")
            var mode = MainViewModel.PreviewMode.MODE_2V
            var suffix = "ST_2x1";
            when(i){
                0 -> if(selectedOptions.EXPORT_ST) {
                    // same as defaults
                    mode = MainViewModel.PreviewMode.MODE_2V
                    suffix = "ST_2x1"
                } else { continue }
                1 -> if(selectedOptions.EXPORT_ST_CROSSVIEW) {
                    mode = MainViewModel.PreviewMode.MODE_ST_CROSSVIEW
                    suffix = "ST_CROSSVIEW";
                } else { continue }
                2 -> if(selectedOptions.EXPORT_4V) {
                    mode = MainViewModel.PreviewMode.MODE_4V
                    suffix = "4V_2x2";
                } else { continue }
                3 -> if(selectedOptions.EXPORT_4V_ST) {
                    mode = MainViewModel.PreviewMode.MODE_4V_ST
                    suffix = "4V_ST_2x2";
                } else { continue }
                else -> continue
            }
            numExportsPending++
            exportModeList.add(Triple(mode,MapType.MAP_ALBEDO,"${filename}_${suffix}.png"))
            if(selectedOptions.EXPORT_DISPARITY_MAPS){
                numExportsPending++
                exportModeList.add(Triple(mode,MapType.MAP_DISPARITY,"${filename}_${suffix}_depth.png"))
            }
        }

        // disable button if no modes selected
        // or throw visible error here
        if(exportModeList.size == 0) {
            return;
        }

        // hide controls and show progress bar
        val mainControlsWrapper: View? = findViewById(R.id.fragment_container_view)
        mainControlsWrapper?.visibility = View.GONE
        mainControlsWrapper?.invalidate()
        val bar = findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progress_bar)
        bar?.visibility = View.VISIBLE
        bar?.max = exportModeList.size
        quadView?.visibility = View.GONE
        preview2DSurface?.visibility = View.GONE
        // jog for redraw
//        val mainView = window.decorView.findViewById<View>(android.R.id.content)
//        mainView?.visibility = View.GONE
//        mainView?.visibility = View.VISIBLE
//        mainView?.invalidate()
        mainViewModel.exportBatch(exportModeList)
    }

    fun saveBitmap(outputBitmap: Bitmap?, filename: String): Pair<Boolean,String> {
        if(outputBitmap == null){
            return Pair(false,"error exporting $filename outputBitmap missing")
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
                // ${finalOutputUri?.path}
                return Pair(true,"Image Saved to /Pictures/LIFToolsExports")
            }catch(ex: Exception){
                Log.e(TAG, ex.message, ex)
                return Pair(false,"error exporting $filename ${ex.message}")
            }
//            finally{
//                //
//            }
        }
    }

    fun onTapSurface(view: View) {
        if(viewingIntro || numExportsPending > 0){
            return
        }
        mainUIHidden = !mainUIHidden
        val visNext = if(mainUIHidden){
            View.GONE
        }else{
            View.VISIBLE
        }
        findViewById<View>(R.id.image_info_text).visibility = visNext
        findViewById<View>(R.id.fragment_container_view).visibility = visNext
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
