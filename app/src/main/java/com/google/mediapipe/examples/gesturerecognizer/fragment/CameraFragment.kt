package com.google.mediapipe.examples.gesturerecognizer.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.gesturerecognizer.*
import com.google.mediapipe.examples.gesturerecognizer.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.google.mediapipe.examples.gesturerecognizer.R


class CameraFragment : Fragment(), GestureRecognizerHelper.GestureRecognizerListener {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private lateinit var gestureRecognizerHelper: GestureRecognizerHelper
    private val viewModel: MainViewModel by activityViewModels()

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var gameResultOverlay: View
    private lateinit var yourGestureContainer: View
    private lateinit var yourGestureValue: TextView

    private val roundHandler = Handler(Looper.getMainLooper())
    private var countdownSeconds = 3

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            findNavController().navigate(R.id.action_camera_to_permissions)
        }

        backgroundExecutor.execute {
            if (this::gestureRecognizerHelper.isInitialized && gestureRecognizerHelper.isClosed()) {
                gestureRecognizerHelper.setupGestureRecognizer()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::gestureRecognizerHelper.isInitialized) {
            backgroundExecutor.execute { gestureRecognizerHelper.clearGestureRecognizer() }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()

        binding.viewFinder.post { setUpCamera() }

        backgroundExecutor.execute {
            gestureRecognizerHelper = GestureRecognizerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
                minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
                currentDelegate = viewModel.currentDelegate,
                gestureRecognizerListener = this
            )
        }

        gameResultOverlay = binding.root.findViewById(R.id.gameResultOverlay)
        yourGestureContainer = binding.root.findViewById(R.id.your_gesture_container)
        yourGestureValue = binding.root.findViewById(R.id.your_gesture_value)

        gameResultOverlay.findViewById<Button>(R.id.playAgainButton).setOnClickListener {
            hideGameResultOverlay()
            viewModel.resetGame()
            Handler(Looper.getMainLooper()).postDelayed({ startNewRound() }, 500)
        }

        gameResultOverlay.findViewById<Button>(R.id.backToMenuButton).setOnClickListener {
            hideGameResultOverlay()
            findNavController().navigate(R.id.action_camera_to_menu)
        }

        observeViewModel()

        viewModel.resetGame()
        startNewRound()
    }

    private fun observeViewModel() {
        viewModel.playerScore.observe(viewLifecycleOwner) { binding.playerScoreText.text = "You: $it" }
        viewModel.botScore.observe(viewLifecycleOwner) { binding.botScoreText.text = "Bot: $it" }
        viewModel.gameMessage.observe(viewLifecycleOwner) { binding.gameMessageText.text = it }
        viewModel.playerMove.observe(viewLifecycleOwner) { move ->
            if (move != Move.NONE) {
                yourGestureContainer.visibility = View.VISIBLE
                yourGestureValue.visibility = View.VISIBLE
                val gestureName = when (move) {
                    Move.ROCK -> "Rock"
                    Move.PAPER -> "Paper"
                    Move.SCISSORS -> "Scissors"
                    else -> ""
                }
                yourGestureValue.text = gestureName
            } else {
                yourGestureValue.text = ""
                yourGestureValue.visibility = View.GONE
            }
        }
        viewModel.botMove.observe(viewLifecycleOwner) { move ->
            val drawable = when (move) {
                Move.ROCK -> R.drawable.ic_rock
                Move.PAPER -> R.drawable.ic_paper
                Move.SCISSORS -> R.drawable.ic_scissors
                else -> 0
            }
            if (drawable != 0) {
                binding.botMoveImage.setImageResource(drawable)
                binding.botMoveImage.visibility = View.VISIBLE
            }
        }
    }

    private fun showGameResultOverlay() {
        val player = viewModel.playerScore.value ?: 0
        val bot = viewModel.botScore.value ?: 0

        val scoreText = gameResultOverlay.findViewById<TextView>(R.id.dialogScore)
        scoreText.text = "You $player : $bot Bot"

        gameResultOverlay.visibility = View.VISIBLE
    }

    private fun hideGameResultOverlay() {
        gameResultOverlay.visibility = View.GONE
    }

    private fun startNewRound() {
        viewModel.setGameState(GameState.COUNTDOWN)
        viewModel.setPlayerMove(Move.NONE)

        countdownSeconds = 3
        showCountdownTick()
    }

    private fun showCountdownTick() {
        when (countdownSeconds) {
            3 -> viewModel.setGameMessage("3")
            2 -> viewModel.setGameMessage("2")
            1 -> viewModel.setGameMessage("1")
            0 -> {
                viewModel.setGameMessage("Show!")
                roundHandler.postDelayed({ processResult() }, 500)
                return
            }
        }

        countdownSeconds--
        roundHandler.postDelayed({ showCountdownTick() }, 1000)
    }

    private fun processResult() {
        viewModel.setGameState(GameState.AWAITING_RESULT)

        Handler(Looper.getMainLooper()).postDelayed({
            viewModel.generateBotMove()
            viewModel.determineWinner()

            val playerScore = viewModel.playerScore.value ?: 0
            val botScore = viewModel.botScore.value ?: 0

            if (playerScore >= viewModel.maxScore || botScore >= viewModel.maxScore) {
                viewModel.setGameState(GameState.GAME_OVER)
                showGameResultOverlay()
                return@postDelayed
            }

            viewModel.setPlayerMove(Move.NONE) // Reset gesture after round

            viewModel.setGameState(GameState.SHOW_RESULT)
            Handler(Looper.getMainLooper()).postDelayed({ startNewRound() }, 1000)
        }, 500)
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor) { image -> recognizeHand(image) }
            }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e("CameraFragment", "Use case binding failed", exc)
        }
    }

    private fun recognizeHand(imageProxy: ImageProxy) {
        if (this::gestureRecognizerHelper.isInitialized) {
            gestureRecognizerHelper.recognizeLiveStream(imageProxy = imageProxy)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = binding.viewFinder.display.rotation
    }

    override fun onResults(resultBundle: GestureRecognizerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread

            if (viewModel.gameState.value == GameState.COUNTDOWN || viewModel.gameState.value == GameState.AWAITING_RESULT) {
                val gestureName = resultBundle.results.firstOrNull()
                    ?.gestures()?.firstOrNull()
                    ?.firstOrNull()?.categoryName() ?: ""

                val move = when (gestureName) {
                    "Open_Palm" -> Move.PAPER
                    "Closed_Fist", "Thumb_Down" -> Move.ROCK
                    "Victory" -> Move.SCISSORS
                    else -> Move.NONE
                }

                if (move != Move.NONE) viewModel.setPlayerMove(move)
            }

            if (resultBundle.results.isNotEmpty()) {
                val result = resultBundle.results.first()
                if (result.landmarks().isNotEmpty()) {
                    binding.overlay.setResults(result, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)
                }
            } else {
                binding.overlay.clear()
            }

            binding.overlay.invalidate()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }
}