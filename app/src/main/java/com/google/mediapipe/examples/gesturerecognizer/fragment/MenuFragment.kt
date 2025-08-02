package com.google.mediapipe.examples.gesturerecognizer.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.gesturerecognizer.databinding.FragmentMenuBinding // Upewnij się, że ta nazwa jest poprawna
import com.google.mediapipe.examples.gesturerecognizer.R

class MenuFragment : Fragment() {

    private var _binding: FragmentMenuBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Ustawienie listenera dla przycisku "Play CPU"
        binding.playCpuButton.setOnClickListener {
            // Nawigacja do ekranu z kamerą
            findNavController().navigate(R.id.action_menu_to_camera)
        }

        // Ustawienie listenera dla przycisku "Play Multiplayer"
        binding.playMultiplayerButton.setOnClickListener {
            // Wyświetlenie komunikatu, tak jak opisałeś
            android.widget.Toast.makeText(
                requireContext(),
                "Available soon",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}