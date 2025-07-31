package com.google.mediapipe.examples.gesturerecognizer

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

// Definiuje możliwe ruchy w grze
enum class Move {
    ROCK, PAPER, SCISSORS, NONE
}

// Definiuje możliwe stany gry
enum class GameState {
    STARTING, COUNTDOWN, AWAITING_RESULT, SHOW_RESULT, GAME_OVER
}

class MainViewModel : ViewModel() {
    // Stare zmienne do konfiguracji MediaPipe
    private var _delegate: Int = GestureRecognizerHelper.DELEGATE_CPU
    private var _minHandDetectionConfidence: Float = GestureRecognizerHelper.DEFAULT_HAND_DETECTION_CONFIDENCE
    private var _minHandTrackingConfidence: Float = GestureRecognizerHelper.DEFAULT_HAND_TRACKING_CONFIDENCE
    private var _minHandPresenceConfidence: Float = GestureRecognizerHelper.DEFAULT_HAND_PRESENCE_CONFIDENCE
    val currentDelegate: Int get() = _delegate
    val currentMinHandDetectionConfidence: Float get() = _minHandDetectionConfidence
    val currentMinHandTrackingConfidence: Float get() = _minHandTrackingConfidence
    val currentMinHandPresenceConfidence: Float get() = _minHandPresenceConfidence

    // Nowe zmienne LiveData do zarządzania stanem gry
    private val _playerScore = MutableLiveData(0)
    val playerScore: LiveData<Int> = _playerScore

    private val _botScore = MutableLiveData(0)
    val botScore: LiveData<Int> = _botScore

    private val _gameState = MutableLiveData(GameState.STARTING)
    val gameState: LiveData<GameState> = _gameState

    private val _gameMessage = MutableLiveData("Naciśnij Play, aby zacząć")
    val gameMessage: LiveData<String> = _gameMessage

    private val _playerMove = MutableLiveData(Move.NONE)
    val playerMove: LiveData<Move> = _playerMove

    private val _botMove = MutableLiveData(Move.NONE)
    val botMove: LiveData<Move> = _botMove

    val maxScore = 3

    // Funkcje do zmiany stanu gry
    fun setGameState(newState: GameState) {
        _gameState.value = newState
    }

    fun setGameMessage(message: String) {
        _gameMessage.value = message
    }

    fun setPlayerMove(move: Move) {
        _playerMove.value = move
    }

    fun generateBotMove() {
        _botMove.value = Move.values().random()
    }

    fun determineWinner() {
        val pMove = _playerMove.value
        val bMove = _botMove.value

        if (pMove == Move.NONE) {
            _botScore.value = (_botScore.value ?: 0) + 1
            setGameMessage("Nie pokazano gestu! Punkt dla bota.")
            return
        }

        if (pMove == bMove) {
            setGameMessage("Remis!")
        } else if ((pMove == Move.ROCK && bMove == Move.SCISSORS) ||
            (pMove == Move.PAPER && bMove == Move.ROCK) ||
            (pMove == Move.SCISSORS && bMove == Move.PAPER)
        ) {
            _playerScore.value = (_playerScore.value ?: 0) + 1
            setGameMessage("Wygrałeś rundę!")
        } else {
            _botScore.value = (_botScore.value ?: 0) + 1
            setGameMessage("Bot wygrał rundę.")
        }

        if(_playerScore.value == maxScore) {
            setGameMessage("WYGRANA!")
            setGameState(GameState.GAME_OVER)
        } else if (_botScore.value == maxScore) {
            setGameMessage("PORAŻKA!")
            setGameState(GameState.GAME_OVER)
        }
    }

    fun resetGame() {
        _playerScore.value = 0
        _botScore.value = 0
        _playerMove.value = Move.NONE
        _botMove.value = Move.NONE
        setGameState(GameState.STARTING)
    }

    // Stare funkcje
    fun setDelegate(delegate: Int) {
        _delegate = delegate
    }

    fun setMinHandDetectionConfidence(confidence: Float) {
        _minHandDetectionConfidence = confidence
    }

    fun setMinHandTrackingConfidence(confidence: Float) {
        _minHandTrackingConfidence = confidence
    }

    fun setMinHandPresenceConfidence(confidence: Float) {
        _minHandPresenceConfidence = confidence
    }
}