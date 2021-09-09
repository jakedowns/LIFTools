package com.jakedowns.LIFTools.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView


class PreviewSurfaceView: SurfaceView, SurfaceHolder.Callback {

    val TAG: String = "LightEx"

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        setWillNotDraw(false)
        holder.addCallback(this)
        Log.i(TAG,"preview surfaceview constructor")
    }

    private var mBitmap: Bitmap? = null

//    override fun onAttachedToWindow() {
//        super.onAttachedToWindow()
//        Log.i(TAG,"attached to window: setting will not draw false")
//        setWillNotDraw(false)
//    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
//        Log.i(TAG,"onDraw $width $height $measuredWidth $measuredHeight "+canvas
//            ?.width.toString()+" "+canvas?.height.toString())
        canvas?.drawColor(Color.parseColor("#1c0d2b"))
        if(canvas != null && mBitmap != null){
            Log.i(TAG,"drawing bitmap "+mBitmap!!.width+" "+mBitmap!!.height)
            val centerX = (width - mBitmap!!.width)/2.toFloat()
            val centerY = (height - mBitmap!!.height)/2.toFloat()
            canvas.drawBitmap(mBitmap!!, centerX, centerY, null)
        }
    }

    fun setImageBitmap(bitmap: Bitmap?) {
        mBitmap = bitmap
//        Log.i(TAG,"setting mBitmap")
        invalidate()
        postInvalidate()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder, format: Int, width: Int,
        height: Int
    ) {
//        Log.i(TAG,"preview surface changed")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // TODO Auto-generated method stub
//        Log.i(TAG,"surfaceCreated setting will not draw false")
        setWillNotDraw(false)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // TODO Auto-generated method stub
    }
}