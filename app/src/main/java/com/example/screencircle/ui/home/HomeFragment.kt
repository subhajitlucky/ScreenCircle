package com.example.screencircle.ui.home

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.screencircle.R
import com.example.screencircle.databinding.FragmentHomeBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupChart()

        viewModel.todayUsage.observe(viewLifecycleOwner) { seconds ->
            val totalSeconds = seconds ?: 0L
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            binding.tvScreenTime.text = String.format("%02dh %02dm", hours, minutes)
            binding.tvSecondsDetail.text = String.format("%,d seconds", totalSeconds)
        }

        viewModel.weeklyData.observe(viewLifecycleOwner) { weeklyData ->
            updateChart(weeklyData)
            updateWeeklyStats(weeklyData)
        }
    }

    private fun setupChart() {
        binding.barChart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setTouchEnabled(false)
            setDrawGridBackground(false)
            setDrawBorders(false)
            
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textColor = ContextCompat.getColor(requireContext(), R.color.md_theme_light_onSurface)
            }
            
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = Color.parseColor("#E0E0E0")
                axisMinimum = 0f
                textColor = ContextCompat.getColor(requireContext(), R.color.md_theme_light_onSurface)
            }
            
            axisRight.isEnabled = false
            
            animateY(500)
        }
    }

    private fun updateChart(weeklyData: WeeklyData) {
        val entries = weeklyData.usageHours.mapIndexed { index, hours ->
            BarEntry(index.toFloat(), hours)
        }

        val dataSet = BarDataSet(entries, "Screen Time").apply {
            color = ContextCompat.getColor(requireContext(), R.color.md_theme_light_primary)
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.md_theme_light_onSurface)
            valueTextSize = 10f
            setDrawValues(false)
        }

        val barData = BarData(dataSet).apply {
            barWidth = 0.5f
        }

        binding.barChart.apply {
            data = barData
            xAxis.valueFormatter = IndexAxisValueFormatter(weeklyData.dayLabels)
            invalidate()
        }
    }

    private fun updateWeeklyStats(weeklyData: WeeklyData) {
        val totalHours = weeklyData.totalSeconds / 3600
        val totalMinutes = (weeklyData.totalSeconds % 3600) / 60
        binding.tvWeeklyTotal.text = if (totalHours > 0) {
            String.format("%dh %dm", totalHours, totalMinutes)
        } else {
            String.format("%dm", totalMinutes)
        }

        val avgHours = weeklyData.averageSeconds / 3600
        val avgMinutes = (weeklyData.averageSeconds % 3600) / 60
        binding.tvDailyAverage.text = if (avgHours > 0) {
            String.format("%dh %dm", avgHours, avgMinutes)
        } else {
            String.format("%dm", avgMinutes)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadWeeklyData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
