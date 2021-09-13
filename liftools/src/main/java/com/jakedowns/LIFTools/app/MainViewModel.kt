package com.jakedowns.LIFTools.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.TextView
import androidx.lifecycle.*
import com.leiainc.androidsdk.photoformat.MultiviewImage
import com.leiainc.androidsdk.photoformat.MultiviewImageDecoder
import com.leiainc.androidsdk.sbs.MultiviewSynthesizer2
import com.jakedowns.LIFTools.app.utils.ExtraTypeCoercion.toBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.graphics.Bitmap

import android.graphics.RectF
import java.io.File
import android.graphics.BitmapFactory
import android.view.View
import java.io.ByteArrayOutputStream
import java.lang.Exception
import android.widget.Toast
import com.leiainc.androidsdk.photoformat.MultiviewFileType


class MainViewModel(private val app: Application): AndroidViewModel(app) {
    val TAG: String = "LIFTools"

    val quadBitmapLiveData : MutableLiveData<Bitmap> by lazy {
        MutableLiveData<Bitmap>()
    }

    val parsedFileCountLiveData : MutableLiveData<Int> by lazy {
        MutableLiveData<Int>(0)
    }

    val exportsRemainingCountLiveData : MutableLiveData<Int> by lazy {
        MutableLiveData<Int>(0)
    }

    enum class PreviewMode {
        MODE_2V,
        MODE_4V_ST,
        MODE_4V,
        MODE_ST_CROSSVIEW,
        MODE_4V_2D
    }

    enum class MapType {
        MAP_ALBEDO,
        MAP_DISPARITY
    }

    var multiviewImage: MultiviewImage? = null
    val dimensions: BitmapFactory.Options = BitmapFactory.Options()
    var mainActivity: MainActivity? = null
    var preview2DSurface: PreviewSurfaceView? = null
    var currentPreviewMode: PreviewMode? = null
    var mCurrentFilepath: String? = null
    var mCurrentFilename: String? = null
    var mCurrentFileType: MultiviewFileType? = null
    var mCurrentImageIsStacked: Boolean = false

    init {
        //loadLifImageOnDisk()
    }

    fun openSelectedFile(fileUri: Uri) {
        /**
         * Save the document to [SharedPreferences]. We're able to do this, and use the
         * uri saved indefinitely, because we called [ContentResolver.takePersistableUriPermission]
         * up in [onActivityResult].
         */
//        getSharedPreferences(TAG, Context.MODE_PRIVATE).edit {
//            putString(LAST_OPENED_URI_KEY, documentUri.toString())
//        }

//        val fragment = ActionOpenDocumentFragment.newInstance(documentUri)
//        supportFragmentManager.transaction {
//            add(R.id.container, fragment, DOCUMENT_FRAGMENT_TAG)
//        }

        // Document is open, so get rid of the call to action view.
//        noDocumentView.visibility = View.GONE
        loadLFImageFromUri(fileUri)
    }

//    private fun loadLifImageOnDisk() {
//        val context = app.applicationContext
//
//        //        val fileUri = DiskUtils.saveResourceToFile(context, R.raw.apollo_low_contrast_2x1)
//        //        val fileUri = DiskUtils.saveResourceToFile(context, R.raw.farm_lif)
//        //        val fileUri = DiskUtils.saveResourceToFile(context, R.raw.optician_2x2)
//        val fileUri = DiskUtils.saveResourceToFile(context, R.raw.pint)
//        loadLFImageFromUri(fileUri!!)
//    }

    fun exportBatch(exportModeList: ArrayList<Triple<PreviewMode, MapType, String>>) = viewModelScope.launch(Dispatchers.IO) {
        exportsRemainingCountLiveData.postValue(exportModeList.size)
        for (i in 0 until exportModeList.size) {
            val (mode,map_type,filename) = exportModeList[i]
//            val type = when(map_type){
//                MapType.MAP_ALBEDO -> "ALBEDO"
//                MapType.MAP_DISPARITY -> "DISPARITY"
//            }
            val outputBitmap = generateBitmap(map_type,mode)
            if(mainActivity!=null){
                val (savedSuccessfully,message) = mainActivity!!.saveBitmap(outputBitmap,filename)
                mainActivity!!.runOnUiThread(Runnable { Toast.makeText(mainActivity, message, Toast.LENGTH_SHORT).show() })
            }
            val next = if(exportsRemainingCountLiveData.value?:0 <= 1){
                -1
            }else{
                exportsRemainingCountLiveData.value?.minus(1)?:-1
            }
            exportsRemainingCountLiveData.postValue(next)
        }
        return@launch
    }

    private fun loadLFImageFromUri(fileUri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val context = app.applicationContext
        multiviewImage = null; // clear out old one if any
        mCurrentFileType = null;

        /* This function searches for the Uri of the file name stored on the internal storage as 'farm-lif.jpg'. */
//        val fileInputStream = context.resources.openRawResource(R.raw.apollo_low_contrast_2x1)
//        fileInputStream.use {
//            val imageBytes = IOUtils.toByteArray(it)
//            val multiviewImage = MultiviewImageDecoder.getDefault().decode(imageBytes, 500 * 517)
//
//            /*  Decoder returns null if */
//            if (multiviewImage != null) {
//                val synthesizer2 = MultiviewSynthesizer2.createMultiviewSynthesizer(context)
//                synthesizer2.populateDisparityMaps(multiviewImage)
//
//                val quadBitmap = synthesizer2.toQuadBitmap(multiviewImage)
//                quadBitmapLiveData.postValue(quadBitmap)
//                return@launch
//            }
//        }

        dimensions.inJustDecodeBounds = true
        val pfd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(fileUri, "r")
        BitmapFactory.decodeFileDescriptor(pfd?.fileDescriptor,null,dimensions);

//            Log.i(TAG,dimensions.outWidth.toString()+" "+dimensions.outHeight.toString())
        val tempMultiviewImage = MultiviewImageDecoder.getDefault().decode(
            context,
            fileUri,
            dimensions.outWidth * dimensions.outHeight)
        /*  Decoder returns null if */
        if (tempMultiviewImage != null) {
            multiviewImage = tempMultiviewImage

            val decoder = MultiviewImageDecoder.getDefault()

            // TODO: store decoded filetype
            val fileType = decoder.getFileType(context, fileUri)
            mCurrentFileType = fileType
            //Log.i(TAG,"MultiviewImageDecoder "+fileType)

            // cant redraw quad from this thread
            // post a message to the main activity and ask it to do it
            val nextValue = parsedFileCountLiveData.value?.plus(1)?:0
            parsedFileCountLiveData.postValue(nextValue)
            return@launch
        }

//                val d3 = (multiviewImage.viewPoints[2]).disparity;
//                val d4 = (multiviewImage.viewPoints[3]).disparity;
//                val test = FieldUtils.readField(synthesizer2, "b", true)
//                val test2 = FieldUtils.readField(test, "a", true)
//                Log.w("TAG",d1.toString())
//                quadBitmapLiveData.postValue(d1);

        quadBitmapLiveData.postValue(null)
        mainActivity?.onErrorLoadingFromFile()
    }

    fun generateBitmap(type: MapType, mode: PreviewMode): Bitmap? {
        var comboBitmap: Bitmap? = null
        val comboImage: Canvas

        val width: Int
        val height: Int

        val dLeft: Bitmap?
        val dRight: Bitmap?
        val dFarLeft: Bitmap?
        val dFarRight: Bitmap?

        val use1VMode = multiviewImage?.viewPoints?.size == 1

        val vp0 = multiviewImage?.viewPoints?.getOrNull(0)
        val vp1 = multiviewImage?.viewPoints?.getOrNull(1)
        val vp2 = multiviewImage?.viewPoints?.getOrNull(2)
        val vp3 = multiviewImage?.viewPoints?.getOrNull(3)

        val synthesizer2: MultiviewSynthesizer2? = if(mainActivity?.IS_LEIA_DEVICE == true){
            MultiviewSynthesizer2.createMultiviewSynthesizer(app.applicationContext)
        }else{
            null
        }
        try{
            synthesizer2?.populateDisparityMaps(multiviewImage)
        }catch(e: Exception){
            e.printStackTrace()
        }
        try{
            val useSynthesizer2 = (mode === PreviewMode.MODE_4V || use1VMode) && type === MapType.MAP_ALBEDO
            if(useSynthesizer2) {
                val quadBitmap = synthesizer2?.toQuadBitmap(multiviewImage)
                return quadBitmap
            }
        }catch(e: Exception){
            e.printStackTrace()
        }

        // TODO: if 1V mode detected, split image out of synthesized quadview
//        synthesizer2?.toTiledBitmap()

        when(mode){
            PreviewMode.MODE_4V -> {
                // real 4v
                if(type === MapType.MAP_DISPARITY){
                    dFarLeft = vp0?.disparity
                    dLeft = vp1?.disparity
                    dRight = vp2?.disparity
                    dFarRight = vp3?.disparity
                }else{
                    dFarLeft = vp0?.albedo
                    dLeft = vp1?.albedo
                    dRight = vp2?.albedo
                    dFarRight = vp3?.albedo
                }

                if(
                    dFarLeft != null
                    && dLeft != null
                    && dRight != null
                    && dFarRight != null
                ){
//                    if(mCurrentImageIsStacked){
//                        width = dimensions.outWidth * 2
//                        height = dimensions.outHeight * 2
//                    }else{
//                        width = dimensions.outWidth
//                        height = dimensions.outHeight
//                    }
                    width = dLeft.width * 2
                    height = dLeft.height * 2
                    comboBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    comboImage = Canvas(comboBitmap)

                    comboImage.drawBitmap(dFarLeft, 0f, 0f, null)
                    comboImage.drawBitmap(dLeft, dLeft.width.toFloat(), 0f, null)
                    comboImage.drawBitmap(dRight, 0f, dLeft.height.toFloat(), null)
                    comboImage.drawBitmap(dFarRight, dLeft.width.toFloat(), dLeft
                        .height.toFloat(), null)
                }
            }
            PreviewMode.MODE_4V_ST -> {
                if(type === MapType.MAP_DISPARITY){
                    dLeft = vp0?.disparity
                    dRight = vp1?.disparity
                }else{
                    dLeft = vp0?.albedo
                    dRight = vp1?.albedo
                }

                if(dLeft != null && dRight != null){
                    // force 4V from 2V
                    if(mCurrentImageIsStacked){
                        // Stacked Grid
                        if(multiviewImage?.viewPoints?.size === 4){
                            width = dLeft.width * 2
                            height = dLeft.height * 2
                        }else{
                            width = dLeft.width * 2
                            height = dLeft.height * 2
                        }
                    }else{
                        // Non-Stacked Grid
                        if(multiviewImage?.viewPoints?.size === 4){
                            width = dLeft.width
                            height = dLeft.height
                        }else{
                            // nonstacked 2x1 2V -> 4V
                            width = dLeft.width * 2
                            height = dLeft.height * 2
                        }
                    }

                    comboBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    comboImage = Canvas(comboBitmap)
                    comboImage.drawBitmap(dLeft, 0f, 0f, null)
                    comboImage.drawBitmap(dLeft, dLeft.width.toFloat(), 0f, null)
                    comboImage.drawBitmap(dRight, 0f, dLeft.height.toFloat(), null)
                    comboImage.drawBitmap(
                        dRight, dLeft.width.toFloat(), dLeft.height
                            .toFloat(), null)
                }

            }
            else -> {
                // 2V 2x1 SBS
                if(type === MapType.MAP_DISPARITY){
                    dLeft = vp0?.disparity
                    dRight = vp1?.disparity
                }else{
                    dLeft = vp0?.albedo
                    dRight = vp1?.albedo
                }

                if(dLeft != null && dRight != null){
                    width = dLeft.width * 2 //dimensions.outWidth
                    height = dLeft.height //dimensions.outHeight
                    comboBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    comboImage = Canvas(comboBitmap)
                    if(mode == PreviewMode.MODE_ST_CROSSVIEW){
                        comboImage.drawBitmap(dRight, 0f, 0f, null)
                        comboImage.drawBitmap(dLeft, dLeft.width.toFloat(), 0f, null)
                    }else{
                        comboImage.drawBitmap(dLeft, 0f, 0f, null)
                        comboImage.drawBitmap(dRight, dLeft.width.toFloat(), 0f, null)
                    }
                }


            }
        }

        return comboBitmap
    }

    // extension function to convert bitmap to byte array
    fun Bitmap.toByteArray():ByteArray{
        ByteArrayOutputStream().apply {
            compress(Bitmap.CompressFormat.PNG,10,this)
            return toByteArray()
        }
    }

    fun redrawQuad() {
        if(multiviewImage == null){
            Log.i(TAG,"redrawQuad: mvi not found")
            return
        }
        if(mainActivity != null){
            if(
                mainActivity?.viewingIntro == true
                || mainActivity?.mViewingDontateModal?:false
                || exportsRemainingCountLiveData.value?:0 > 0
                || mainActivity?.mWaitingForFirstRenderAfterImport?:false
            ){
                return
            }
        }
        val context = app.applicationContext
//        Log.w(TAG,"how many viewpoints? "+multiviewImage?.viewPoints?.size);

        val synthesizer2: MultiviewSynthesizer2? = if(mainActivity?.IS_LEIA_DEVICE == true){
            MultiviewSynthesizer2.createMultiviewSynthesizer(context)
        }else{
            null
        }
        try {
            synthesizer2?.populateDisparityMaps(multiviewImage)
        }catch(ex: Exception){
            Log.i(TAG,ex.message?:"error")
        }


        val sharedPref = app.getSharedPreferences(app.applicationContext.resources
            .getResourceEntryName(R.string
                .user_prefs_key),Context.MODE_PRIVATE) ?: return

        val res = app.applicationContext.resources;
        val prefName4VAI = res.getString(R.string.export_opt_cb_4V_AI_key)
        val prefName4VST = res.getString(R.string.export_opt_cb_4V_ST_key)
        val prefNameDEPTHMAP = res.getString(R.string.export_opt_cb_disparity_maps_key)
        val prefNameSTCV = res.getString(R.string.export_opt_cb_CV_2x1_key)
        val prefNamePREVIEW3D = res.getString(R.string.export_opt_cb_PREVIEW_3D_key)
        val prefNameCurrentFilename = res.getString(R.string.pref_string_state_selected_filename)
//        val prefNameCurrentFilepath = res.getString(R.string.pref_string_state_selected_filepath)

        val EXPORT_4V_AI = sharedPref.getInt(prefName4VAI,0).toBoolean()
        val EXPORT_4V_ST = sharedPref.getInt(prefName4VST,0).toBoolean()
        val EXPORT_DISPARITY_MAPS = sharedPref.getInt(prefNameDEPTHMAP,0).toBoolean()
        val EXPORT_ST_CROSSVIEW = sharedPref.getInt(prefNameSTCV,0).toBoolean()
        val PREVIEW_3D = if(mainActivity?.IS_LEIA_DEVICE != true){
            false
        }else{
            sharedPref.getInt(prefNamePREVIEW3D,1).toBoolean()
        }
        mCurrentFilename = sharedPref.getString(prefNameCurrentFilename, "unknown-filename")

        mCurrentImageIsStacked = !(mCurrentFilename?.contains("_2x1.")?:false
                || mCurrentFilename?.contains("_2x2.")?:false)

//        Log.i(TAG,"$prefName4VST $EXPORT_4V_ST")
//        Log.i(TAG,"$prefNameDEPTHMAP $EXPORT_DISPARITY_MAPS")
//        Log.i(TAG,"$prefNameSTCV $EXPORT_ST_CROSSVIEW")
//        Log.i(TAG,"$prefNamePREVIEW3D $PREVIEW_3D")
        Log.i(TAG,"$mCurrentImageIsStacked")

        // TODO: move to a determine mode fn
        var mode: PreviewMode = PreviewMode.MODE_2V
        if(multiviewImage?.viewPoints?.size === 2){
            if(PREVIEW_3D){
                if(multiviewImage?.e?.name ?: "unknown" == "NEURAL_STEREO" && !EXPORT_4V_ST){
                    mode = PreviewMode.MODE_4V
                }else{
                    mode = PreviewMode.MODE_4V_ST
                }
            }else{
                if(EXPORT_4V_ST){
                    mode = PreviewMode.MODE_4V_ST
                } else if(EXPORT_ST_CROSSVIEW){
                    mode = PreviewMode.MODE_ST_CROSSVIEW
                }else{
                    mode = PreviewMode.MODE_2V
                }
            }
        }else if(multiviewImage?.viewPoints?.size === 4){
            if(PREVIEW_3D){
                if(EXPORT_4V_ST){
                    mode = PreviewMode.MODE_4V_ST
                }else{
                    mode = PreviewMode.MODE_4V
                }
            }else{
                if(EXPORT_4V_ST){
                    mode = PreviewMode.MODE_4V_ST
                }else{
                    mode = PreviewMode.MODE_4V_2D
                }
            }
        }

        val type = when(EXPORT_DISPARITY_MAPS) {
            true -> MapType.MAP_DISPARITY
            false -> MapType.MAP_ALBEDO
        }

        currentPreviewMode = mode;

        val textArea: TextView? = mainActivity?.findViewById(R.id.image_info_text)
        textArea?.text = "viewPoints: "+(multiviewImage?.viewPoints?.size.toString())
        textArea?.append("\npreview type: $type")
        textArea?.append("\npreview mode: "+mode.name)
        textArea?.append("\ndims: "+dimensions.outWidth.toString()+"x"+dimensions.outHeight.toString())
        textArea?.append("\nfilename: $mCurrentFilename")
        textArea?.append("\nfile type: $mCurrentFileType")
//        textArea?.append("\ngain: $mCurrentFilename")
//        textArea?.append("\nconvergence: $mCurrentFilename")
//        textArea?.append("\nDOF: $mCurrentFilename")

        val useSynthesizer2 = multiviewImage?.e?.name ?: "unknown" == "NEURAL_STEREO"
                && !EXPORT_4V_ST
                && currentPreviewMode == PreviewMode.MODE_4V
                && PREVIEW_3D
                && !EXPORT_DISPARITY_MAPS

        if(useSynthesizer2){
            val quadBitmap = synthesizer2?.toQuadBitmap(multiviewImage)
            quadBitmapLiveData.postValue(quadBitmap)
        } else {
            val comboBitmap = generateBitmap(type, mode)
            if(comboBitmap!=null){
                preview2DSurface?.setImageBitmap(
                    scale2(
                        res.displayMetrics.widthPixels,
                        res.displayMetrics.heightPixels,
                        comboBitmap
                    )
                )
                quadBitmapLiveData.postValue(comboBitmap.copy(Bitmap.Config
                    .ARGB_8888,true))
            }
        }

        if(PREVIEW_3D){
            mainActivity?.quadView?.visibility = View.VISIBLE
            preview2DSurface?.visibility = View.GONE
        }else{
            mainActivity?.quadView?.visibility = View.GONE
            preview2DSurface?.visibility = View.VISIBLE
        }

    }

    private fun scale2(width: Int, height: Int, originalImage: Bitmap): Bitmap{
        val background: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config
            .ARGB_8888)

        val originalWidth: Int = originalImage.width
        val originalHeight: Int = originalImage.height

        val canvas = Canvas(background)

        val scale: Float;
        // scale down
        if(originalWidth > originalHeight){
            // landscape
            scale = width.toFloat() / originalWidth.toFloat()
        }else{
            // sq,portrait
            scale = height.toFloat() / originalHeight.toFloat()
        }

        Log.i(TAG,"scale $scale width $width height $height originalWidth " +
                "$originalWidth originalHeight $originalHeight")

        val scaledWidth = originalWidth * scale
        val scaledHeight = originalHeight * scale
        val xTranslation: Float = 0f// (width - scaledWidth)/2.0f
        val yTranslation: Float = (height - scaledHeight)/2.0f

        val transformation = Matrix()
        transformation.postTranslate(xTranslation, yTranslation)
        transformation.preScale(scale, scale)

        val paint = Paint()
        paint.isFilterBitmap = true

        canvas.drawBitmap(originalImage, transformation, paint)

        return background
    }
}
