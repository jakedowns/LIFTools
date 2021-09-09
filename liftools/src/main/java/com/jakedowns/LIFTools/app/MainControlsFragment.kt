package com.jakedowns.LIFTools.app;

import androidx.fragment.app.Fragment

import android.os.Bundle

import android.view.ViewGroup

import android.view.LayoutInflater

import android.app.Activity
import android.view.View

class MainControlsFragment: Fragment(R.layout.main_controls_fragment) {
    private var listener: EventListener? = null
    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        if (activity is EventListener) {
            listener = activity
        } else {
            // Throw an error!
        }
    }

    /**
     *
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v: View = inflater.inflate(R.layout.main_controls_fragment, container, false)

        return v
    }

    fun onCheckboxClicked(v: View){
        listener!!.onCheckboxClicked(v)
    }

    fun onExportClicked(v: View){
        listener!!.onExportClicked(v)
    }

    fun onPickNewClicked(v: View){
        listener!!.onPickNewClicked(v)
    }
}
