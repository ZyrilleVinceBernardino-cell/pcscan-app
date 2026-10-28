package com.pcscan.pcscan.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.navOptions
import androidx.navigation.fragment.findNavController
import com.pcscan.pcscan.R

class OcrFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ocr, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Keep the existing OCR entry fragment, but forward immediately to the actual scanner.
        if (savedInstanceState == null) {
            view.post {
                if (isAdded) {
                    findNavController().navigate(
                        R.id.action_ocrFragment_to_ocrScanningFragment,
                        null,
                        navOptions {
                            popUpTo(R.id.ocrFragment) { inclusive = true }
                        }
                    )
                }
            }
        }
    }
}
