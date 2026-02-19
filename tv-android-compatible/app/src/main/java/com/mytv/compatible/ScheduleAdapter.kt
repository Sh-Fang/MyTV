package com.mytv.compatible

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class ScheduleAdapter(private val context: Context) : BaseAdapter() {

    private var items: List<ScheduleItem> = emptyList()

    fun submitList(newItems: List<ScheduleItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_schedule, parent, false)
        val item = items[position]

        view.findViewById<TextView>(R.id.tvIndicator).text = if (item.current) "▶" else " "

        val tvTime = view.findViewById<TextView>(R.id.tvTime)
        tvTime.text = "${item.startsAt} – ${item.endsAt}"
        tvTime.setTextColor(if (item.current) Color.WHITE else Color.parseColor("#FFAAAAAA"))

        val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
        tvTitle.text = item.title
        if (item.current) {
            tvTitle.setTextColor(Color.parseColor("#FF44AAFF"))
            tvTitle.textSize = 18f
            tvTitle.setTypeface(null, Typeface.BOLD)
        } else {
            tvTitle.setTextColor(Color.WHITE)
            tvTitle.textSize = 16f
            tvTitle.setTypeface(null, Typeface.NORMAL)
        }
        return view
    }
}
