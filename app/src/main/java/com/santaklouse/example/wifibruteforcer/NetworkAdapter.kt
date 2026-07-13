package com.santaklouse.example.wifibruteforcer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class NetworkAdapter(private val networks: List<NetworkItem>) :
    RecyclerView.Adapter<NetworkAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSsid: TextView = view.findViewById(R.id.tvSsid)
        val tvBssid: TextView = view.findViewById(R.id.tvBssid)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = networks[position]
        holder.tvSsid.text = item.ssid
        holder.tvBssid.text = "BSSID: ${item.bssid} • ${item.signal} dBm"

        val (text, color) = when (item.status) {
            Status.SUCCESS -> "✓ Пароль подошёл: ${item.passwordUsed}" to android.R.color.holo_green_dark
            Status.TESTING -> "Проверка паролей…" to android.R.color.holo_orange_dark
            Status.FAILED -> "Ни один пароль не подошёл" to android.R.color.darker_gray
            Status.SECURED -> "Защищённая сеть • ожидает проверки" to android.R.color.darker_gray
            Status.OPEN -> "Открытая сеть" to android.R.color.holo_blue_dark
            Status.UNSUPPORTED -> "Enterprise/OWE: этот тип не проверяется" to android.R.color.darker_gray
        }
        holder.tvStatus.text = text
        holder.tvStatus.setTextColor(ContextCompat.getColor(holder.itemView.context, color))
    }

    override fun getItemCount() = networks.size
}
