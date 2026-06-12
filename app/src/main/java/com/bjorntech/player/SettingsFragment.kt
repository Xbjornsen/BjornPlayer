package com.bjorntech.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bjorntech.player.databinding.FragmentSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()

    private val repoUrl = "https://github.com/Xbjornsen/BjornPlayer"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        // Appearance
        binding.settingsThemeValue.text = SettingsManager.themeLabel(ctx)
        binding.settingsTheme.setOnClickListener { showThemeDialog() }

        // Playback
        binding.settingsAutoplaySwitch.isChecked = SettingsManager.isAutoplayOnLaunch(ctx)
        binding.settingsAutoplay.setOnClickListener {
            val newValue = !binding.settingsAutoplaySwitch.isChecked
            binding.settingsAutoplaySwitch.isChecked = newValue
            SettingsManager.setAutoplayOnLaunch(ctx, newValue)
        }

        // Library
        binding.settingsRescan.setOnClickListener {
            viewModel.loadMusic()
            Toast.makeText(ctx, "Rescanning music library…", Toast.LENGTH_SHORT).show()
        }

        // Updates
        binding.settingsVersion.text = "Version ${BuildConfig.VERSION_NAME}"
        binding.settingsCheckUpdates.setOnClickListener {
            (activity as? MainActivity)?.checkForUpdateManual()
        }

        // About
        binding.settingsGithub.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl)))
        }
        binding.settingsAboutFooter.text = "BjornPlayer ${BuildConfig.VERSION_NAME}"
    }

    private fun showThemeDialog() {
        val labels = arrayOf("System default", "Light", "Dark")
        val modes = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES
        )
        val current = modes.indexOf(SettingsManager.getThemeMode(requireContext())).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Theme")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                SettingsManager.setThemeMode(requireContext(), modes[which])
                _binding?.settingsThemeValue?.text = labels[which]
                dialog.dismiss()
                // AppCompat recreates the activity to apply the new night mode.
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
