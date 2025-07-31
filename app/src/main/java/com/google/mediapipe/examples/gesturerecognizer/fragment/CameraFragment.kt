package com.google.mediapipe.examples.gesturerecognizer.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.gesturerecognizer.*
import com.google.mediapipe.examples.gesturerecognizer.R
import com.google.mediapipe.examples.gesturerecognizer.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), GestureRecognizerHelper.GestureRecognizerListener {

    private companion object {
        private const val TAG = "Hand gesture recognizer"
    }

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

    private var countDownTimer: CountDownTimer? = null

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
            viewModel.setDelegate(gestureRecognizerHelper.currentDelegate)
            backgroundExecutor.execute { gestureRecognizerHelper.clearGestureRecognizer() }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        countDownTimer?.cancel()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()

        binding.viewFinder.post {
            setUpCamera()
        }

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

        setupGameControls()
        observeViewModel()

        // Rozpocznij grę po wejściu na ekran
        viewModel.resetGame()
        startNewRound()
    }

    private fun setupGameControls() {
        binding.playAgainButton.setOnClickListener {
            viewModel.resetGame()
            startNewRound()
        }
        binding.backToMenuButton.setOnClickListener {
            findNavController().navigate(R.id.action_camera_to_menu)
        }
    }

    private fun observeViewModel() {
        viewModel.playerScore.observe(viewLifecycleOwner) { score ->
            binding.playerScoreText.text = "Ty: $score"
        }

        viewModel.botScore.observe(viewLifecycleOwner) { score ->
            binding.botScoreText.text = "Bot: $score"
        }

        viewModel.gameMessage.observe(viewLifecycleOwner) { message ->
            binding.gameMessageText.text = message
        }

        viewModel.gameState.observe(viewLifecycleOwner) { state ->
            binding.gameOverGroup.isVisible = state == GameState.GAME_OVER
            if (state != GameState.SHOW_RESULT) {
                binding.botMoveImage.visibility = View.INVISIBLE
            }
        }

        viewModel.botMove.observe(viewLifecycleOwner) { move ->
            val botMoveDrawable = when (move) {
                Move.ROCK -> R.drawable.ic_rock
                Move.PAPER -> R.drawable.ic_paper
                Move.SCISSORS -> R.drawable.ic_scissors
                else -> 0
            }
            if (botMoveDrawable != 0) {
                binding.botMoveImage.setImageResource(botMoveDrawable)
                binding.botMoveImage.visibility = View.VISIBLE
            }
        }
    }

    private fun startNewRound() {
        viewModel.setGameState(GameState.COUNTDOWN)
        viewModel.setPlayerMove(Move.NONE) // Resetuj ruch gracza na początku rundy

        countDownTimer = object : CountDownTimer(4000, 1000) { // 4s total, 1s interval
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000
                if (secondsLeft > 0) {
                    viewModel.setGameMessage(secondsLeft.toString())
                } else {
                    viewModel.setGameMessage("Pokaż!")
                }
            }

            override fun onFinish() {
                processResult()
            }
        }.start()
    }

    private fun processResult() {
        viewModel.setGameState(GameState.AWAITING_RESULT)

        // Dajmy MediaPipe chwilę na ostatnie rozpoznanie
        Handler(Looper.getMainLooper()).postDelayed({
            // Bot wykonuje swój ruch
            viewModel.generateBotMove()
            // Określ zwycięzcę
            viewModel.determineWinner()

            // Zmień stan na pokazywanie wyniku
            viewModel.setGameState(GameState.SHOW_RESULT)

            // Jeśli gra nie jest skończona, zacznij nową rundę po 2 sekundach
            if (viewModel.gameState.value != GameState.GAME_OVER) {
                Handler(Looper.getMainLooper()).postDelayed({
                    startNewRound()
                }, 2000)
            }
        }, 500) // 0.5s opóźnienia
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")
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
                it.setAnalyzer(backgroundExecutor) { image ->
                    recognizeHand(image)
                }
            }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
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
            // Upewnij się, że binding wciąż istnieje (zabezpieczenie)
            if (_binding == null) {
                return@runOnUiThread
            }

            // 1. Przeniesiona logika - teraz wszystko dzieje się w bezpiecznym wątku UI
            // Przetwarzaj wynik tylko w odpowiednim stanie gry
            if (viewModel.gameState.value == GameState.COUNTDOWN || viewModel.gameState.value == GameState.AWAITING_RESULT) {
                val gestureName = resultBundle.results.firstOrNull()
                    ?.gestures()?.firstOrNull()
                    ?.firstOrNull()
                    ?.categoryName()
                    ?: ""

                val move = when (gestureName) {
                    "Open_Palm" -> Move.PAPER
                    "Closed_Fist", "Thumb_Down" -> Move.ROCK
                    "Victory" -> Move.SCISSORS
                    else -> Move.NONE
                }

                if (move != Move.NONE) {
                    // Ta linia powodowała błąd - teraz jest w bezpiecznym miejscu
                    viewModel.setPlayerMove(move)
                }
            }

            // 2. Logika rysowania (z poprzednich poprawek)
            // Sprawdź, czy są jakiekolwiek wyniki, zanim ich użyjesz
            if (resultBundle.results.isNotEmpty()) {
                val result = resultBundle.results.first()
                // Rysuj tylko, jeśli w wyniku są punkty dłoni
                if (result.landmarks().isNotEmpty()) {
                    binding.overlay.setResults(
                        result,
                        resultBundle.inputImageHeight,
                        resultBundle.inputImageWidth,
                        RunningMode.LIVE_STREAM
                    )
                }
            } else {
                // Jeśli nie ma wyników (brak dłoni), wyczyść nakładkę
                binding.overlay.clear()
            }

            // Odśwież widok w obu przypadkach
            binding.overlay.invalidate()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }
}