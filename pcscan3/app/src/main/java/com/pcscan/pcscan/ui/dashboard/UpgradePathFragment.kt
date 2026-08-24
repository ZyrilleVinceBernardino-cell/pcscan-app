package com.pcscan.pcscan.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentUpgradePathBinding

class UpgradePathFragment : Fragment() {

    private var _binding: FragmentUpgradePathBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUpgradePathBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        arguments?.getString(ARG_SELECTED_COMPONENT)
            ?.takeIf { it.isNotBlank() }
            ?.let(viewModel::ensureSelectedComponent)

        viewModel.upgradePathScreenState.observe(viewLifecycleOwner) { state ->
            when (state) {
                ScreenState.Idle -> Unit
                ScreenState.Loading -> binding.tvBasedOn.text = "Loading upgrade path..."
                is ScreenState.Success -> {
                    val uiState = state.data
                    binding.tvBasedOn.text = uiState.basedOnText
                }

                is ScreenState.Error -> {
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        val selectedComponent = arguments?.getString(ARG_SELECTED_COMPONENT).orEmpty()

        binding.btnYesBuild.setOnClickListener {
            findNavController().navigate(
                R.id.action_upgradePathFragment_to_upgradeCurrentFragment,
                Bundle().apply {
                    putString(UpgradeCurrentFragment.ARG_SELECTED_COMPONENT, selectedComponent)
                }
            )
        }

        binding.btnNoBuild.setOnClickListener {
            findNavController().navigate(
                R.id.action_upgradePathFragment_to_noBuildYetFragment,
                Bundle().apply {
                    putString(NoBuildYetFragment.ARG_SELECTED_COMPONENT, selectedComponent)
                }
            )
        }
    }

    companion object {
        const val ARG_SELECTED_COMPONENT = "selectedComponent"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
