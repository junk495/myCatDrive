package com.maisonsmd.catdrive.ui.home

import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.maisonsmd.catdrive.MainActivity
import com.maisonsmd.catdrive.R
import com.maisonsmd.catdrive.databinding.FragmentHomeBinding
import com.maisonsmd.catdrive.lib.BitmapHelper
import com.maisonsmd.catdrive.lib.NavigationData
import com.maisonsmd.catdrive.ui.ActivityViewModel
import com.maisonsmd.catdrive.utils.ServiceManager
import timber.log.Timber


class HomeFragment : Fragment() {
    private var mUiBinding: FragmentHomeBinding? = null
    private var mDebugImage = false

    private val binding get() = mUiBinding!!

    private fun displayNavigationData(data: NavigationData?) {
        val bitmap =
            if (!mDebugImage) data?.actionIcon?.bitmap
            else resources.getDrawable(R.drawable.roundabout).toBitmap()

        binding.imgTurnIcon.setImageBitmap(bitmap)
        /*
        val bh = BitmapHelper()
        val compressed = bh.compressBitmap(bitmap, Size(32, 32))
        binding.imgScaled.setImageDrawable(
            BitmapHelper.AliasingDrawableWrapper(
                bitmap?.scale(32, 32, false)?.toDrawable(resources)
            )
        )
        binding.imgFinal.setImageDrawable(BitmapHelper.AliasingDrawableWrapper(compressed.toDrawable(resources)))
        // Timber.e(BitmapHelper().toBase64(compressed))
        */

        if (data == null) {
            binding.txtRoadName.text = "---"
            binding.txtRoadAdditionalInfo.text = "---"
            binding.txtDistance.text = "---"
            binding.txtEta.text = "---"
            return
        }

        binding.txtRoadName.text = data.nextDirection.nextRoad
        binding.txtRoadAdditionalInfo.text = data.nextDirection.nextRoadAdditionalInfo
        binding.txtDistance.text = data.nextDirection.distance
        binding.txtEta.text = "${data.eta.ete} - ${data.eta.eta} - ${data.eta.distance}"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mUiBinding = FragmentHomeBinding.inflate(inflater, container, false)

        mUiBinding!!.btnDisconnect.setOnClickListener {
            Timber.i("Disconnect request");
            ServiceManager.stopBroadcastService(activity as MainActivity)
        }

        val viewModel = ViewModelProvider(requireActivity())[ActivityViewModel::class.java]
        viewModel.navigationData.observe(viewLifecycleOwner) {
            displayNavigationData(it)
        }
        viewModel.speed.observe(viewLifecycleOwner) {
            binding.txtSpeed.text = "$it km/h"
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mUiBinding = null
    }
}