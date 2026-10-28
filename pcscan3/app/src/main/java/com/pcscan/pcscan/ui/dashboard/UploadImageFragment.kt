package com.pcscan.pcscan.ui.dashboard

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentUploadImageBinding

class UploadImageFragment : Fragment() {

    private var _binding: FragmentUploadImageBinding? = null
    private val binding get() = _binding!!

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { imageUri: Uri? ->
        if (imageUri != null && isAdded) {
            redirectToSharedLoadingPipeline(imageUri.toString())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUploadImageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener {
            safePopBackToDashboard()
        }
        binding.uploadDropzone.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
    }

    private fun safePopBackToDashboard() {
        try {
            if (_binding == null || !isAdded) return
            val navController = findNavController()
            if (!navController.popBackStack(R.id.dashboardFragment, false)) {
                navController.navigate(R.id.dashboardFragment)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun redirectToSharedLoadingPipeline(imageUri: String) {
        if (!isAdded) return
        findNavController().navigate(
            R.id.action_uploadImageFragment_to_uploadImageLoadingFragment,
            Bundle().apply {
                putString(ARG_SELECTED_IMAGE_URI, imageUri)
            }
        )
    }

    private fun showFriendlyError(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_SELECTED_IMAGE_URI = "selectedImageUri"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
