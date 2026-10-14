package com.example.ime.security.vault.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.ime.security.vault.crypto.VaultCryptoManager
import com.example.ime.security.vault.data.VaultEntryEntity
import com.example.ime.security.vault.data.VaultGroupEntity
import com.example.ime.security.vault.totp.TotpGenerator

sealed class KdbxListItem {
    data class GroupItem(val group: VaultGroupEntity, val entryCount: Int, val isExpanded: Boolean) : KdbxListItem()
    data class EntryItem(val entry: VaultEntryEntity) : KdbxListItem()
}

class KdbxEntriesAdapter(
    private val onGroupClicked: (VaultGroupEntity) -> Unit,
    private val onEntryClicked: (VaultEntryEntity) -> Unit,
    private val onEntryMoreClicked: (VaultEntryEntity, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_ENTRY = 1
    }

    private var items = listOf<KdbxListItem>()

    fun submitList(newItems: List<KdbxListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is KdbxListItem.GroupItem -> TYPE_GROUP
            is KdbxListItem.EntryItem -> TYPE_ENTRY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GROUP) {
            val v = inflater.inflate(R.layout.item_kdbx_folder, parent, false)
            GroupViewHolder(v)
        } else {
            val v = inflater.inflate(R.layout.item_kdbx_entry, parent, false)
            EntryViewHolder(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is KdbxListItem.GroupItem -> (holder as GroupViewHolder).bind(item, onGroupClicked)
            is KdbxListItem.EntryItem -> (holder as EntryViewHolder).bind(item.entry, onEntryClicked, onEntryMoreClicked)
        }
    }

    override fun getItemCount(): Int = items.size

    class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val txtArrow: TextView = view.findViewById(R.id.txt_folder_arrow)
        private val txtName: TextView = view.findViewById(R.id.txt_folder_name)
        private val txtCount: TextView = view.findViewById(R.id.txt_folder_count)

        fun bind(item: KdbxListItem.GroupItem, onClick: (VaultGroupEntity) -> Unit) {
            txtName.text = item.group.name
            txtCount.text = "${item.entryCount} items"
            txtArrow.text = if (item.isExpanded) "▼" else "▶"
            itemView.setOnClickListener { onClick(item.group) }
        }
    }

    class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val txtTitle: TextView = view.findViewById(R.id.txt_entry_title)
        private val txtUsername: TextView = view.findViewById(R.id.txt_entry_username)
        private val totpCircle: TotpCircleProgressView = view.findViewById(R.id.totp_circle_preview)
        private val btnMore: TextView = view.findViewById(R.id.btn_entry_more)

        fun bind(
            entry: VaultEntryEntity,
            onClick: (VaultEntryEntity) -> Unit,
            onMore: (VaultEntryEntity, View) -> Unit
        ) {
            txtTitle.text = entry.title
            txtUsername.text = entry.username ?: "(No username)"

            // Handle TOTP indicator
            val totpEncrypted = entry.totpSecretEncrypted
            if (totpEncrypted != null && totpEncrypted.isNotEmpty()) {
                val totpSecret = VaultCryptoManager.decryptToString(totpEncrypted)
                if (!totpSecret.isNullOrBlank()) {
                    val config = TotpGenerator.parseConfig(totpSecret)
                    if (config != null) {
                        totpCircle.visibility = View.VISIBLE
                        val rem = TotpGenerator.getRemainingSeconds(periodSeconds = config.periodSeconds)
                        val frac = TotpGenerator.getProgressFraction(periodSeconds = config.periodSeconds)
                        totpCircle.setProgress(frac, rem)
                    } else {
                        totpCircle.visibility = View.GONE
                    }
                } else {
                    totpCircle.visibility = View.GONE
                }
            } else {
                totpCircle.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(entry) }
            btnMore.setOnClickListener { onMore(entry, it) }
        }
    }
}
