package com.faldo.hsk_quest.ui.practice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.databinding.FragmentPracticeBinding
import com.faldo.hsk_quest.ui.battle.AudioRecorderHelper
import java.io.File

/**
 * Practice & Debug Laboratory fragment for Phase 2D input shells:
 * - Hanzi stroke writing canvas with vector capture & stroke threshold check
 * - Audio pronunciation recording and playback
 */
class PracticeFragment : Fragment() {

    private var _binding: FragmentPracticeBinding? = null
    private val binding get() = _binding!!

    private lateinit var audioHelper: AudioRecorderHelper
    private var recordedAudioFile: File? = null

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startRecordingFlow()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Microphone permission is required for speaking challenges.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPracticeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        audioHelper = AudioRecorderHelper(requireContext())

        setupWritingCanvas()
        setupAudioRecorder()
    }

    private fun setupWritingCanvas() {
        binding.canvasWriting.onStrokeCountChanged = { count ->
            binding.tvStrokeCount.text = "Strokes: $count / 2"
            if (count >= 2) {
                binding.tvStrokeFeedback.text = "✓ Ready to strike!"
                binding.tvStrokeFeedback.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_jade)
                )
            } else {
                binding.tvStrokeFeedback.text = "Awaiting input"
                binding.tvStrokeFeedback.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_text_muted)
                )
            }
        }

        binding.btnClearCanvas.setOnClickListener {
            binding.canvasWriting.clearCanvas()
        }

        binding.btnValidateCanvas.setOnClickListener {
            if (binding.canvasWriting.isValidHanziAttempt(minStrokes = 2)) {
                val vectors = binding.canvasWriting.getStrokeVectors()
                val totalPoints = vectors.sumOf { it.size }
                Toast.makeText(
                    requireContext(),
                    "Attack valid! Captured ${vectors.size} strokes ($totalPoints touch points).",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Attack failed: at least 2 strokes required to cast Hanzi magic!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setupAudioRecorder() {
        binding.btnRecordAudio.setOnClickListener {
            if (hasAudioPermission()) {
                startRecordingFlow()
            } else {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnStopAudio.setOnClickListener {
            val file = audioHelper.stopRecording()
            recordedAudioFile = file
            binding.btnRecordAudio.isEnabled = true
            binding.btnStopAudio.isEnabled = false
            binding.btnPlayAudio.isEnabled = file != null && file.exists()

            if (file != null) {
                val sizeKb = file.length() / 1024
                binding.tvAudioStatus.text = "Recorded audio: ${file.name} ($sizeKb KB)"
                binding.tvAudioStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.hq_jade)
                )
            } else {
                binding.tvAudioStatus.text = "Recording cancelled or failed"
            }
        }

        binding.btnPlayAudio.setOnClickListener {
            val file = recordedAudioFile ?: return@setOnClickListener
            binding.btnPlayAudio.isEnabled = false
            binding.tvAudioStatus.text = "Playing back audio..."

            val started = audioHelper.playAudio(file) {
                binding.btnPlayAudio.isEnabled = true
                binding.tvAudioStatus.text = "Playback finished"
            }
            if (!started) {
                binding.btnPlayAudio.isEnabled = true
                binding.tvAudioStatus.text = "Failed to play back recording"
            }
        }
    }

    private fun startRecordingFlow() {
        val target = File(requireContext().cacheDir, "practice_audio_${System.currentTimeMillis()}.m4a")
        val started = audioHelper.startRecording(target)
        if (started) {
            binding.btnRecordAudio.isEnabled = false
            binding.btnStopAudio.isEnabled = true
            binding.btnPlayAudio.isEnabled = false
            binding.tvAudioStatus.text = "🔴 Recording in progress... Speak now!"
            binding.tvAudioStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.hq_crimson)
            )
        } else {
            Toast.makeText(requireContext(), "Failed to initialize recorder", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        audioHelper.releaseAll()
        _binding = null
    }
}
