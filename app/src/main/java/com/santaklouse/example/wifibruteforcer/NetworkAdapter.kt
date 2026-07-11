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
        holder.tvBssid.text = "BSSID: ${item.bssid}"

        when (item.status) {
            Status.SUCCESS -> {
                holder.tvStatus.text = "✓ Подключено (пароль: ${item.passwordUsed})"
                holder.tvStatus.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.holo_green_dark))
            }
            Status.FAILED -> {
                holder.tvStatus.text = "✗ Пароль не подошёл"
                holder.tvStatus.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray))
            }
            Status.OPEN -> {
                holder.tvStatus.text = "Открытая сеть"
                holder.tvStatus.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.holo_blue_dark))
            }
        }
    }

    override fun getItemCount() = networks.size
}