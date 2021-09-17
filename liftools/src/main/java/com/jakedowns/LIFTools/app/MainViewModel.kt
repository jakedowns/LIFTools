package com.jakedowns.LIFTools.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.database.Cursor
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

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.View
import android.view.Window
import java.io.ByteArrayOutputStream
import java.lang.Exception
import com.leiainc.androidsdk.photoformat.MultiviewFileType
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

class SelectedExportOptions(
    val PREVIEW_3D:Boolean = false,
    val EXPORT_4V_AI:Boolean = false,
    val EXPORT_4V_ST:Boolean = false,
    val EXPORT_2V_ST:Boolean = false,
    val EXPORT_ST_CROSSVIEW:Boolean = false,
    val EXPORT_DISPARITY_MAPS:Boolean = false,
    val EXPORT_SPLIT_VIEWS:Boolean = false
)

class ExportParamBag(
    val mode:MainViewModel.PreviewMode,
    val mapType:MainViewModel.MapType,
    val fileName:String,
    val specificView:MainViewModel.NamedView?=null
)

class MainViewModel(private val app: Application): AndroidViewModel(app) {
    val TAG: String = "LIFTools"

    enum class NamedView {
        FL,
        L,
        R,
        FR
    }

    val quadBitmapLiveData : MutableLiveData<Bitmap> by lazy {
        MutableLiveData<Bitmap>()
    }

    val parsedFileCountLiveData : MutableLiveData<Int> by lazy {
        MutableLiveData<Int>(0)
    }

    val exportsRemainingCountLiveData : MutableLiveData<Int> by lazy {
        MutableLiveData<Int>(0)
    }

    var mComboPreviewMode: ComboPreviewMode = ComboPreviewMode.NONE

    enum class ComboPreviewMode {
        NONE,
        SOURCE_ONE,
        SOURCE_TWO,
        SOURCE_FOUR
    }

    enum class PreviewMode {
        MODE_2V,
        MODE_4V_ST,
        MODE_4V,
        MODE_ST_CROSSVIEW,
        MODE_SPLIT_VIEWS // more of an ExportMode than a PreviewMode :G
    }

    enum class MapType {
        MAP_ALBEDO,
        MAP_DISPARITY
    }

    var multiviewImage: MultiviewImage? = null
    val mFileDimensions = ArrayList<BitmapFactory.Options>()
    var mainActivity: MainActivity? = null
    var preview2DSurface: PreviewSurfaceView? = null
    var currentPreviewMode: PreviewMode? = null
    lateinit var mCurrentFilenames: ArrayList<String>
    var mCurrentFileType: MultiviewFileType? = null
    var mCurrentImageIsStacked: Boolean = false
    var mMultiBitmaps = ArrayList<Bitmap>()

    init {
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
        mComboPreviewMode = ComboPreviewMode.NONE // flag that we're NOT in combo builder mode
        loadLIFImageFromUri(fileUri)
    }

    @Throws(Exception::class)
    fun openMultipleFiles(uris: ArrayList<Uri>){
        //Log.i(TAG,"opening images for combining!");
        multiviewImage = null; // clear out old one if any
        mCurrentFileType = null;
        mMultiBitmaps = ArrayList<Bitmap>()
        mFileDimensions.clear()
        val context = app.applicationContext

        when(uris.size){
            1 -> {
                // draw single bitmap with option to convert to stereo
                mComboPreviewMode = ComboPreviewMode.SOURCE_ONE
            }
            2 -> {
                // draw stereo bitmap
                mComboPreviewMode = ComboPreviewMode.SOURCE_TWO
            }
            4 -> {
                // draw quad bitmap
                mComboPreviewMode = ComboPreviewMode.SOURCE_FOUR
            }
            else -> {
                mComboPreviewMode = ComboPreviewMode.NONE // flag that we're NOT in combo builder mode
                mainActivity?.runOnUiThread {
                    mainActivity?.onErrorLoadingFromFile()
                }
            }
        }

        // TODO: tell frontend we're done LOADING and we're starting DECODING
        // TODO: filename safety check here: don't accept _2x2, _2x1 or LIF when selecting multiple files

        // get image dimensions and create bitmaps
        var i = 0;
        var prevWidth = 0;
        var prevHeight = 0;
        while(i < uris.size){
            val uri = uris[i];
            var bitmap: Bitmap;

            // TODO: pre-scale bitmap to fit display to reduce memory footprint

            // parse Image into Bitmap and cache into memory
            if(Build.VERSION.SDK_INT < 28) {
                bitmap = MediaStore.Images.Media.getBitmap(
                    app.applicationContext.contentResolver,
                    uri
                )
            } else {
                val op = BitmapFactory.Options()
                op.inPreferredConfig = Bitmap.Config.ARGB_8888
                bitmap = BitmapFactory.decodeFile(uri.path,op)
//                val source = ImageDecoder.createSource(app.applicationContext.contentResolver, uri)
//                bitmap = ImageDecoder.decodeBitmap(source,Bitmap.Config.ARGB_8888)
//                bitmap = ImageDecoder.decodeDrawable(source) {
//                    decoder, info, src -> decoder.setOnPartialImageListener {
//                        e: DecodeException? -> true } }
            }
            try {
                val exif = uri.path?.let { ExifInterface(it) }
                val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
                if(orientation == ExifInterface.ORIENTATION_ROTATE_90){
                    val matrix = Matrix()
                    matrix.postRotate(90.0f);
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true);
                }
            }catch(e: Exception){
                Log.e(TAG, "error reading exif")
            }

            mMultiBitmaps.add(bitmap)

            // Read File Dimensions into our ArrayList
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            val pfd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "r")
            BitmapFactory.decodeFileDescriptor(pfd?.fileDescriptor,null,opts);
            if(prevWidth == 0 && prevHeight == 0){
                prevWidth = opts.outWidth
                prevHeight = opts.outHeight
            }else{
                if(opts.outWidth != prevWidth
                    || opts.outHeight != prevHeight
                ){
                    throw Exception("Invalid dimensions. Images must all be the same size");
                }
            }
            mFileDimensions.add(opts)
            i++;
        }

        if(uris.size == 1){
            val dimensions = mFileDimensions[0]

            // go ahead and pre-decode the image since we're already on a background thread
            val tempMultiviewImage = MultiviewImageDecoder.getDefault().decode(
                app.applicationContext,
                uris[0],
                dimensions.outWidth * dimensions.outHeight
            )
            if (tempMultiviewImage != null) {
                multiviewImage = tempMultiviewImage

                val decoder = MultiviewImageDecoder.getDefault()
                val fileType = decoder.getFileType(context, uris[0])
                mCurrentFileType = fileType
            }else{
                Log.e(TAG,"error synthesizing from single image")
                return;
            }
        }

        // tell the MainActivity we finished loading and decoding the images
        val nextValue = parsedFileCountLiveData.value?.plus(1)?:0
        parsedFileCountLiveData.postValue(nextValue)
    }

    fun exportBatch(exportModeList: ArrayList<ExportParamBag>) = viewModelScope.launch(Dispatchers.IO) {
        exportsRemainingCountLiveData.postValue(exportModeList.size)
        // TODO: launch these jobs into a background queue
        for (i in 0 until exportModeList.size) {
            val mode = exportModeList[i].mode
            val mapType = exportModeList[i].mapType
            val filename = exportModeList[i].fileName
            val specificView = exportModeList[i].specificView
            val outputBitmap = smartGenerateBitmap(mapType,mode,specificView)
            if(outputBitmap == null){
                // error generating bitmap
            }else if(mainActivity!=null){
                try{
                    val (savedSuccessfully,message) = mainActivity!!.saveBitmap(outputBitmap,filename)
                }catch(e: Exception){
                    e.printStackTrace()
                }
//                mainActivity!!.runOnUiThread(Runnable {
//                    Toast.makeText(mainActivity, message, Toast.LENGTH_SHORT).show() })
                //outputBitmap.recycle()
            }
            val curr = exportsRemainingCountLiveData.value?:0;
            val next = if(curr < 2){
                -1
            }else{
                kotlin.math.max(curr-1,-1)
            }
            exportsRemainingCountLiveData.postValue(next)
        }
        return@launch
    }

    private fun loadLIFImageFromUri(fileUri: Uri) = viewModelScope.launch(Dispatchers.IO) {
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

        mFileDimensions.clear()
        mFileDimensions.add(BitmapFactory.Options())

        mFileDimensions[0].inJustDecodeBounds = true
        val pfd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(fileUri, "r")
        BitmapFactory.decodeFileDescriptor(pfd?.fileDescriptor,null,mFileDimensions[0]);

//            Log.i(TAG,dimensions.outWidth.toString()+" "+dimensions.outHeight.toString())
        val tempMultiviewImage = MultiviewImageDecoder.getDefault().decode(
            context,
            fileUri,
            mFileDimensions[0].outWidth * mFileDimensions[0].outHeight)
        /*  Decoder returns null if */
        if (tempMultiviewImage != null) {
            multiviewImage = tempMultiviewImage

            val decoder = MultiviewImageDecoder.getDefault()

            val fileType = decoder.getFileType(context, fileUri)
            mCurrentFileType = fileType
            //Log.i(TAG,"MultiviewImageDecoder "+fileType)

            // cant redraw quad from this thread
            // post a message to the main activity and ask it to do it
            // NOTE: could've called runOnUiThread
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
    }

    // TODO: accept scale param, are we talking max res or MAX RES?
    // be mindful of memory usage
    fun smartGenerateBitmap(
        type: MapType,
        mode: PreviewMode,
        specificView:NamedView? = null
    ): Bitmap?{
        if(mComboPreviewMode != ComboPreviewMode.NONE){
            return generateBitmapFromComboImages(type, mode, specificView)
        }
        return generateBitmap(type, mode, specificView)
    }

    // TODO: memoize this and cache results
    fun generateBitmap(
        type: MapType,
        mode: PreviewMode,
        specificView:NamedView? = null
    ): Bitmap? {
        var comboBitmap: Bitmap? = null
        val comboImage: Canvas

        val width: Int
        val height: Int

        var dLeft: Bitmap? = null
        var dRight: Bitmap? = null
        var dFarLeft: Bitmap? = null
        var dFarRight: Bitmap? = null

        val sourceIsOneView = when {
            mComboPreviewMode == ComboPreviewMode.NONE -> {
                mFileDimensions.size == 1
            }
            else -> {
                multiviewImage?.viewPoints?.size == 1
            }
        }

        val vp0 = multiviewImage?.viewPoints?.getOrNull(0)
        val vp1 = multiviewImage?.viewPoints?.getOrNull(1)
        val vp2 = multiviewImage?.viewPoints?.getOrNull(2)
        val vp3 = multiviewImage?.viewPoints?.getOrNull(3)

        val useSynthesizer2 =
            mode === PreviewMode.MODE_4V
            && type === MapType.MAP_ALBEDO
        val synthesizer2 = maybeInitSynthesizer(multiviewImage)
        try{
            if(useSynthesizer2) {
                val quadBitmap = synthesizer2?.toQuadBitmap(multiviewImage)
                return quadBitmap
            }
        }catch(e: Exception){
            e.printStackTrace()
        }

        var oneViewFarLeft: Bitmap? = null
        var oneViewLeft: Bitmap? = null
        var oneViewRight: Bitmap? = null
        var oneViewFarRight: Bitmap? = null
        var splitBitmapCreated = false
        if(sourceIsOneView){
            try{
                val quadBitmap = synthesizer2?.toQuadBitmap(multiviewImage)
                if(quadBitmap != null){
                    val splitBitmap = splitBitmap(quadBitmap, 2, 2)
                    oneViewFarLeft = splitBitmap[0][0]
                    oneViewLeft = splitBitmap[0][1]
                    oneViewRight = splitBitmap[1][0]
                    oneViewFarRight = splitBitmap[1][1]
                    splitBitmapCreated = true
                }
            }catch(e: Exception){
                e.printStackTrace()
            }
        }

        Log.i(TAG,"splitBitmapCreated? ${splitBitmapCreated}")

        if(specificView != null){
            val outMap: Bitmap? = when(type){
                MapType.MAP_ALBEDO -> {
                    when(specificView){
                        NamedView.FL -> vp0?.albedo?:oneViewFarLeft
                        NamedView.L -> vp1?.albedo?:oneViewLeft
                        NamedView.R -> vp2?.albedo?:oneViewRight
                        NamedView.FR -> vp3?.albedo?:oneViewFarRight
                    }
                }
                MapType.MAP_DISPARITY -> {
                    when(specificView){
                        NamedView.FL -> vp0?.disparity
                        NamedView.L -> vp0?.disparity
                        NamedView.R -> vp1?.disparity?:vp0?.disparity
                        NamedView.FR -> vp1?.disparity?:vp0?.disparity
                    }
                }
            }
            outMap?.let {
                return it
            }
        }

        when(mode){
            PreviewMode.MODE_4V -> {
                // real 4v
                if(sourceIsOneView){
                    if(type === MapType.MAP_DISPARITY){
                        // mono depth
                        // TODO: generate stereo of depthmap? :P
                        // or parse as 2V, extract depthmaps
                        dFarLeft = vp0?.disparity
                        dLeft = vp0?.disparity
                        dRight = vp0?.disparity
                        dFarRight = vp0?.disparity
                    }else{
                        // albedo
                        dFarLeft = oneViewFarLeft
                        dLeft = oneViewLeft
                        dRight = oneViewRight
                        dFarRight = oneViewFarRight
                    }
                }else{
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
                    if(sourceIsOneView && type === MapType.MAP_DISPARITY){
                        // mono depth
                        comboBitmap = dLeft;
                    }else{
                        // quad
                        comboBitmap = combine4(
                            dFarLeft,
                            dLeft,
                            dRight,
                            dFarRight
                        )
                    }
                }
            }
            PreviewMode.MODE_4V_ST -> {
                if(sourceIsOneView) {
                    if(type === MapType.MAP_DISPARITY){
                        // Mono disparity by default
                        // TODO: add an option to generate more
                        dLeft = vp0?.disparity
                        dRight = vp0?.disparity
                    }else{
                        dLeft = oneViewLeft
                        dRight = oneViewRight
                    }
                }else{
                    if(type === MapType.MAP_DISPARITY){
                        dLeft = vp0?.disparity
                        dRight = vp1?.disparity
                    }else{
                        dLeft = vp0?.albedo
                        dRight = vp1?.albedo
                    }
                }

                if(dLeft != null && dRight != null){
                    if(sourceIsOneView && type === MapType.MAP_DISPARITY){
                        // mono depth
                        comboBitmap = dLeft
                    }else{
                        comboBitmap = combine2To4(dLeft,dRight)
                    }

                }

            }
            else -> {
                // 2V 2x1 SBS
                if(sourceIsOneView) {
                    if(type === MapType.MAP_DISPARITY){
                        // TODO: when 1V detected: option to export mono depth map
                        //  OR to create a stereo depth map by feeding L/R into network and synthesizing them
                        dLeft = vp0?.disparity
                        dRight = vp0?.disparity
                    }else{
                        // for some reason in 1V,2V H4V binary, these are swapped?
                        dLeft = oneViewRight //oneViewLeft
                        dRight = oneViewLeft //oneViewRight
                    }
                }else{
                    if(type === MapType.MAP_DISPARITY){
                        dLeft = vp0?.disparity
                        dRight = vp1?.disparity
                    }else{
                        dLeft = vp0?.albedo
                        dRight = vp1?.albedo
                    }
                }

                if(dLeft != null && dRight != null){
                    comboBitmap = combine2(mode, dLeft, dRight)
                }
            }
        }

        return comboBitmap
    }

    private fun maybeInitSynthesizer(multiviewImage: MultiviewImage?): MultiviewSynthesizer2? {
        val synthesizer2: MultiviewSynthesizer2? = if(mainActivity?.IS_LEIA_DEVICE == true){
            MultiviewSynthesizer2.createMultiviewSynthesizer(app.applicationContext)
        }else{
            null
        }
        try{
            if(multiviewImage != null){
                synthesizer2?.populateDisparityMaps(multiviewImage)
            }
        }catch(e: Exception){
            e.printStackTrace()
        }
        return synthesizer2
    }

    fun generateBitmapFromComboImages(
        type: MapType,
        mode: PreviewMode,
        specificView:NamedView? = null
    ): Bitmap? {
        var comboBitmap: Bitmap? = null
        val comboImage: Canvas
        val width: Int
        val height: Int
        var i = 0;
        var dLeft: Bitmap? = null
        var dRight: Bitmap? = null
        var dFarLeft: Bitmap? = null
        var dFarRight: Bitmap? = null
        var oneViewFarLeft: Bitmap? = null
        var oneViewLeft: Bitmap? = null
        var oneViewRight: Bitmap? = null
        var oneViewFarRight: Bitmap? = null
        var oneViewCreated = false

        val selectedOpts = getSelectedExportOpts()
        val PREVIEW_3D = selectedOpts.PREVIEW_3D
        val EXPORT_ST_CROSSVIEW = selectedOpts.EXPORT_ST_CROSSVIEW

        val uriOne = mainActivity?.mSelectedComboUris?.get(0);
        val dimensions = mFileDimensions[0];
        if(mMultiBitmaps.size == 1 && uriOne != null){
            // create 4V from Single Image
            if (multiviewImage == null) {
                Log.e(TAG,"multiviewImage missing")
                return null; // TODO generate a nice error message
            }else if(
                mCurrentFileType == MultiviewFileType.H4V_BINARY
                || mCurrentFileType == MultiviewFileType.H4V_XMP
            ){
                // fallback to OG implementation
                // TODO refactor and clean this up
                return generateBitmap(type,mode,specificView)
            }else{
                val synthesizer2 = maybeInitSynthesizer(multiviewImage)
                var quadBitmap: Bitmap? = null;
                try{
                    quadBitmap = synthesizer2?.toQuadBitmap(multiviewImage)
                }catch(e: Exception){
                    e.printStackTrace()
                }
                if(quadBitmap != null){
                    val splitBitmap = splitBitmap(quadBitmap, 2, 2)
                    oneViewFarLeft = splitBitmap[0][0]
                    oneViewLeft = splitBitmap[0][1]
                    oneViewRight = splitBitmap[1][0]
                    oneViewFarRight = splitBitmap[1][1]
                    oneViewCreated = true
                }
            }
        }else if(mMultiBitmaps.size == 2){
            // stereo
            oneViewLeft = mMultiBitmaps[0]
            oneViewRight = mMultiBitmaps[1]
        }else if(mMultiBitmaps.size == 4){
            // quad -> preview as 4V_ST
            oneViewFarLeft = mMultiBitmaps[0]
            oneViewLeft = mMultiBitmaps[1]
            oneViewRight = mMultiBitmaps[2]
            oneViewFarRight = mMultiBitmaps[3]
        }else{
            // error
            return null
        }
        val viewsAvailable = arrayListOf<Boolean>(
            oneViewFarLeft  != null,
            oneViewLeft     != null,
            oneViewRight    != null,
            oneViewFarRight != null
        )
        val viewsAvailableCount = viewsAvailable.count { it }

        if(viewsAvailableCount < 2) {
            throw Exception("need at least 2 bitmaps")
        }

        // generate bitmap based on output mode selection
        when(mode){
            PreviewMode.MODE_4V -> {
                if(viewsAvailableCount == 2) {
                    if(PREVIEW_3D){
                        comboBitmap = combine2To4(
                            oneViewLeft!!,
                            oneViewRight!!,
                        )
                    }else{
                        comboBitmap = combine2(
                            when(EXPORT_ST_CROSSVIEW){
                                true -> PreviewMode.MODE_ST_CROSSVIEW
                                false -> PreviewMode.MODE_2V
                            },
                            oneViewLeft!!,
                            oneViewRight!!,
                        )
                    }
                }else if(viewsAvailableCount == 4){
                    comboBitmap = combine4(
                        oneViewFarLeft!!,
                        oneViewLeft!!,
                        oneViewRight!!,
                        oneViewFarRight!!
                    )
                }
            }
            PreviewMode.MODE_4V_ST -> {
                comboBitmap = combine2To4(oneViewLeft!!,oneViewRight!!)
            }
            else -> {
                // ST, ST_CROSSVIEW
                comboBitmap = combine2(mode,oneViewLeft!!,oneViewRight!!)
            }
        }
        return comboBitmap
    }
    fun combine2(mode:PreviewMode, dLeft: Bitmap, dRight: Bitmap):Bitmap{
        val width = dLeft.width * 2 //dimensions.outWidth
        val height = dLeft.height //dimensions.outHeight
        val comboBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val comboImage = Canvas(comboBitmap)
        if(mode == PreviewMode.MODE_ST_CROSSVIEW){
            comboImage.drawBitmap(dRight, 0f, 0f, null)
            comboImage.drawBitmap(dLeft, dLeft.width.toFloat(), 0f, null)
        }else{
            comboImage.drawBitmap(dLeft, 0f, 0f, null)
            comboImage.drawBitmap(dRight, dLeft.width.toFloat(), 0f, null)
        }
        return comboBitmap
    }
    fun combine2To4(dLeft: Bitmap, dRight: Bitmap):Bitmap{
        val width = dLeft.width * 2
        val height = dLeft.height * 2
        val comboBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val comboImage = Canvas(comboBitmap)
        comboImage.drawBitmap(dLeft, 0f, 0f, null)
        comboImage.drawBitmap(dLeft, dLeft.width.toFloat(), 0f, null)
        comboImage.drawBitmap(dRight, 0f, dLeft.height.toFloat(), null)
        comboImage.drawBitmap(
            dRight, dLeft.width.toFloat(), dLeft.height
                .toFloat(), null)
        return comboBitmap
    }
    fun combine4(dFarLeft: Bitmap,dLeft: Bitmap,dRight: Bitmap,dFarRight: Bitmap):Bitmap{
        val width = dLeft.width * 2
        val height = dLeft.height * 2
        val comboBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val comboImage = Canvas(comboBitmap)

        comboImage.drawBitmap(dFarLeft, 0f, 0f, null)
        comboImage.drawBitmap(dLeft, dLeft.width.toFloat(), 0f, null)
        comboImage.drawBitmap(dRight, 0f, dLeft.height.toFloat(), null)
        comboImage.drawBitmap(dFarRight, dLeft.width.toFloat(), dLeft
            .height.toFloat(), null)

        return comboBitmap
    }
    fun splitBitmap(bitmap: Bitmap, xCount: Int, yCount: Int): Array<Array<Bitmap?>> {
        // Allocate a two dimensional array to hold the individual images.
        val bitmaps = Array(xCount) { arrayOfNulls<Bitmap>(yCount) }
        val width: Int
        val height: Int
        // Divide the original bitmap width by the desired vertical column count
        width = bitmap.width / xCount
        // Divide the original bitmap height by the desired horizontal row count
        height = bitmap.height / yCount
        // Loop the array and create bitmaps for each coordinate
        for (x in 0 until xCount) {
            for (y in 0 until yCount) {
                // Create the sliced bitmap
                bitmaps[x][y] = Bitmap.createBitmap(bitmap, x * width, y * height, width, height)
            }
        }
        // Return the array
        return bitmaps
    }

    // extension function to convert bitmap to byte array
    fun Bitmap.toByteArray():ByteArray{
        ByteArrayOutputStream().apply {
            compress(Bitmap.CompressFormat.PNG,10,this)
            return toByteArray()
        }
    }

    fun redrawQuad() {
        if(mainActivity != null){
            if(
                mainActivity?.mViewingIntro == true
                || mainActivity?.mViewingDontateModal?:false
                || exportsRemainingCountLiveData.value?:0 > 0
                || mainActivity?.mWaitingForFirstRenderAfterImport?:false
            ){
                mainActivity?.quadView?.visibility = View.GONE
                preview2DSurface?.visibility = View.GONE
                return
            }
        }
        val context = app.applicationContext
//        Log.w(TAG,"how many viewpoints? "+multiviewImage?.viewPoints?.size);

        val res = context.resources
        val sharedPref = app.getSharedPreferences(
            res.getResourceEntryName(R.string
                .user_prefs_key),
            Context.MODE_PRIVATE)

        val selectedOpts = getSelectedExportOpts()

        // TODO: retrieve ALL filenames (do we need to keep in prefs? maybe, otherwise we need to package/reload on device rotate)

        mCurrentFilenames = ArrayList<String>()
        if(mComboPreviewMode != ComboPreviewMode.NONE){
            // if _any_ of the images contain this, we need to throw an error in "combine" mode
            mCurrentFilenames = mainActivity?.mSelectedComboFilenames?:mCurrentFilenames
            var i = 0;
            for(f in mCurrentFilenames){
                if(mCurrentFilenames.size > 1){
                    // TODO: block LIF binary / XMP files here too when selecting multiple
                    val badType = f.contains("_2x1")
                    val badType2 = f.contains("_2x2")
                    val prefix = when {
                        badType -> "_2x1"
                        badType2 -> "_2x2"
                        else -> ""
                    }
                    if(badType || badType2){
                        mainActivity?.onErrorLoadingFromFile("cannot combine $prefix images")
                        return
                    }
                }
                i++;
            }
        }else{
            val prefNameCurrentFilename = res.getString(R.string.pref_string_state_selected_filename)
//        val prefNameCurrentFilepath = res.getString(R.string.pref_string_state_selected_filepath)
            mCurrentFilenames.add(sharedPref.getString(prefNameCurrentFilename, "unknown-filename")?:"unknown-filename")
            val mCurrentFilename = mCurrentFilenames[0]
            mCurrentImageIsStacked = !(mCurrentFilename.contains("_2x1.") ?:false
                    || mCurrentFilename.contains("_2x2.") ?:false)
        }

        val mode: PreviewMode = getPreviewMode(selectedOpts)

        val type = when(selectedOpts.EXPORT_DISPARITY_MAPS) {
            true -> MapType.MAP_DISPARITY
            false -> MapType.MAP_ALBEDO
        }

        currentPreviewMode = mode;

        val textArea: TextView? = mainActivity?.findViewById(R.id.image_info_text)
        textArea?.text = "viewPoints: "+(multiviewImage?.viewPoints?.size.toString())
        textArea?.append("\npreview type: $type")
        textArea?.append("\npreview mode: "+mode.name)
        var i = 1;
        for(n in mCurrentFilenames){
            try{
                textArea?.append("\ndims[$i]: "+
                        mFileDimensions[i].outWidth.toString()+
                        "x"+mFileDimensions[i].outHeight.toString())
            }catch(e: Exception){
                e.message?.let { Log.i(TAG, it) }
            }
            try{
                textArea?.append("\nfilenames[$i]: $n")
            }catch(e: Exception){
                e.message?.let { Log.i(TAG, it) }
            }
            i++;
        }
        textArea?.append("\nfile type: $mCurrentFileType")
//        textArea?.append("\ngain: $mCurrentFilename")
//        textArea?.append("\nconvergence: $mCurrentFilename")
//        textArea?.append("\nDOF: $mCurrentFilename")

        if(selectedOpts.PREVIEW_3D){
            mainActivity?.quadView?.visibility = View.VISIBLE
            preview2DSurface?.visibility = View.GONE
        }else{
            mainActivity?.quadView?.visibility = View.GONE
            preview2DSurface?.visibility = View.VISIBLE
        }

        val useSynthesizer2 = mainActivity?.IS_LEIA_DEVICE == true
                && mComboPreviewMode == ComboPreviewMode.NONE
                && multiviewImage?.e?.name ?: "unknown" == "NEURAL_STEREO"
                && !(selectedOpts.EXPORT_4V_ST)
                && currentPreviewMode == PreviewMode.MODE_4V
                && selectedOpts.PREVIEW_3D
                && !(selectedOpts.EXPORT_DISPARITY_MAPS)
        val synthesizer2 = maybeInitSynthesizer(multiviewImage)
        try {
            synthesizer2?.populateDisparityMaps(multiviewImage)
        }catch(ex: Exception){
            Log.i(TAG,ex.message?:"error")
        }
        // When on a Leia Device
        // and !in "combine" mode
        // and !export4VST checked
        // and previewMode is 4V
        // and preview3D is enabled
        // and !exportDisparityMaps
        if(useSynthesizer2){
            // only 3d mode
            val quadBitmap = synthesizer2?.toQuadBitmap(multiviewImage)
            quadBitmapLiveData.postValue(quadBitmap)
        } else {
            val comboBitmap = smartGenerateBitmap(type, mode)
            if(comboBitmap!=null){
                if(selectedOpts.PREVIEW_3D){
                    quadBitmapLiveData.postValue(comboBitmap.copy(Bitmap.Config
                        .ARGB_8888,true))
                }else{
                    preview2DSurface?.setImageBitmap(
                        scale2(
                            res.displayMetrics.widthPixels,
                            res.displayMetrics.heightPixels,
                            comboBitmap
                        )
                    )
                }
            }
        }
    }

    fun captureView(view: View, window: Window, bitmapCallback: (Bitmap)->Unit) {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Above Android O, use PixelCopy
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val location = IntArray(2)
            view.getLocationInWindow(location)
            PixelCopy.request(window,
                Rect(location[0], location[1], location[0] + view.width, location[1] + view.height),
                bitmap,
                {
                    if (it == PixelCopy.SUCCESS) {
                        bitmapCallback.invoke(bitmap)
                    }
                },
                Handler(Looper.getMainLooper()) )
//        } else {
//            val tBitmap = Bitmap.createBitmap(
//                view.width, view.height, Bitmap.Config.RGB_565
//            )
//            val canvas = Canvas(tBitmap)
//            view.draw(canvas)
//            canvas.setBitmap(null)
//            bitmapCallback.invoke(tBitmap)
//        }
    }

    fun getSelectedExportOpts(): SelectedExportOptions {
        val sharedPref = app.getSharedPreferences(app.applicationContext.resources
            .getResourceEntryName(R.string
                .user_prefs_key),Context.MODE_PRIVATE)
        val res = app.applicationContext.resources;
        val prefName4VAI = res.getString(R.string.export_opt_cb_4V_AI_key)
        val prefName4VST = res.getString(R.string.export_opt_cb_4V_ST_key)
        val prefNameDEPTHMAP = res.getString(R.string.export_opt_cb_disparity_maps_key)
        val prefNameSTCV = res.getString(R.string.export_opt_cb_CV_2x1_key)
        val prefName2VST = res.getString(R.string.export_opt_cb_ST_2x1_key)
        val prefNamePREVIEW3D = res.getString(R.string.export_opt_cb_PREVIEW_3D_key)
        val prefNameSplitViews = res.getString(R.string.export_opt_cb_split_views_key)

        val export4VAI = sharedPref.getInt(prefName4VAI,0).toBoolean()
        val export4VST = sharedPref.getInt(prefName4VST,0).toBoolean()
        val exportDisparityMaps = sharedPref.getInt(prefNameDEPTHMAP,0).toBoolean()
        val export2VST = sharedPref.getInt(prefName2VST,0).toBoolean()
        val exportSTCV = sharedPref.getInt(prefNameSTCV,0).toBoolean()
        val exportSplitViews = sharedPref.getInt(prefNameSplitViews,0).toBoolean()
        val preview3D = if(mainActivity?.IS_LEIA_DEVICE != true){
            false
        }else{
            sharedPref.getInt(prefNamePREVIEW3D,1).toBoolean()
        }
        return SelectedExportOptions(
            PREVIEW_3D = preview3D,
            EXPORT_4V_AI = export4VAI,
            EXPORT_4V_ST = export4VST,
            EXPORT_2V_ST = export2VST,
            EXPORT_ST_CROSSVIEW = exportSTCV,
            EXPORT_DISPARITY_MAPS = exportDisparityMaps,
            EXPORT_SPLIT_VIEWS = exportSplitViews,
        )
    }

    private fun getPreviewMode(selectedOpts: SelectedExportOptions): PreviewMode{
        return when {
            selectedOpts.EXPORT_4V_AI -> {
                PreviewMode.MODE_4V
            }
            selectedOpts.EXPORT_4V_ST -> {
                PreviewMode.MODE_4V_ST
            }
            selectedOpts.EXPORT_ST_CROSSVIEW -> {
                when(selectedOpts.PREVIEW_3D) {
                    true -> PreviewMode.MODE_4V_ST
                    else -> PreviewMode.MODE_ST_CROSSVIEW
                }
            }
            else -> {
                when(selectedOpts.PREVIEW_3D) {
                    true -> PreviewMode.MODE_4V_ST
                    else -> PreviewMode.MODE_2V
                }
            }
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

    fun exportEnabled(): Boolean {
        val enabledCount = getNumFilesToExport()
        return enabledCount > 0
    }

    // TODO: adapt this for a new user preference to export L and R as separate files
    // (or FL,L,R,FR as separate files)
    fun getNumFilesToExport(): Int {
        val selectedOpts = getSelectedExportOpts();
        val enabledList = booleanArrayOf(
            selectedOpts.EXPORT_4V_AI,
            selectedOpts.EXPORT_4V_ST,
            selectedOpts.EXPORT_2V_ST,
            selectedOpts.EXPORT_ST_CROSSVIEW,
        )
        val enabledCount = enabledList.count { it }
        return when(selectedOpts.EXPORT_DISPARITY_MAPS) {
            true -> enabledCount * 2
            false -> enabledCount
        }
    }
}
