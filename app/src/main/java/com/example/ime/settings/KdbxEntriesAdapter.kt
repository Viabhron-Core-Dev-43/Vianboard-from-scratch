package com.example.ime.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.ime.security.vault.crypto.VaultCryptoManager
import com.example.ime.security.vault.data.VaultEntryEntity
import com.example.ime.security.vault.data.VaultGroupEntity
import com.example.ime.security.vault.totp.TotpGenerator
import com.example.ime.security.vault.ui.TotpCircleProgressView

sealed class KdbxDisplayItem {
    data class FolderItem(val group: VaultGroupEntity, val entryCount: Int, val isExpanded: Boolean) : KdbxDisplayItem()
    data class EntryItem(val entry: VaultEntryEntity) : KdbxDisplayItem()
}

class KdbxEntriesAdapter(
    private val onFolderClicked: (VaultGroupEntity) -> Unit,
    private val onEntryClicked: (VaultEntryEntity) -> Unit,
    private val onEntryEdit: (VaultEntryEntity) -> Unit,
    private val onEntryDelete: (VaultEntryEntity) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items = listOf<KdbxDisplayItem>()

    fun submitList(newItems: List<KdbxDisplayItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is KdbxDisplayItem.FolderItem -> 0
            is KdbxDisplayItem.EntryItem -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            val view = inflater.inflate(R.layout.item_kdbx_folder, parent, false)
            FolderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_kdbx_entry, parent, false)
            EntryViewHolder(view)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is KdbxDisplayItem.FolderItem -> (holder as FolderViewHolder).bind(item)
            is KdbxDisplayItem.EntryItem -> (holder as EntryViewHolder).bind(item)
        }
    }

    inner class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val txtArrow: TextView = view.findViewById(R.id.txt_folder_arrow)
        private val txtName: TextView = view.findViewById(R.id.txt_folder_name)
        private val txtCount: TextView = view.findViewById(R.id.txt_folder_count)

        fun bind(item: KdbxDisplayItem.FolderItem) {
            txtName.text = item.group.name
            txtArrow.text = if (item.isExpanded) "▼" else "▶"
            txtCount.text = "${item.entryCount} items"
            itemView.setOnClickListener { onFolderClicked(item.group) }
        }
    }

    inner class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val txtTitle: TextView = view.findViewById(R.id.txt_entry_title)
        private val txtUsername: TextView = view.findViewById(R.id.txt_entry_username)
        private val totpCircle: TotpCircleProgressView = view.findViewById(R.id.totp_circle_preview)
        private val btnMore: TextView = view.findViewById(R.id.btn_entry_more)

        fun bind(item: KdbxDisplayItem.EntryItem) {
            val entry = item.entry
            txtTitle.text = entry.title
            txtUsername.text = (entry.username ?: "").ifBlank { entry.urlOrPackage ?: "No username" }

            // TOTP preview
            val totpEncrypted = entry.totpSecretEncrypted
            if (totpEncrypted != null) {
                val totpSecret = VaultCryptoManager.decryptToString(totpEncrypted)
                if (!totpSecret.isNullOrBlank()) {
                    val totpResult = TotpGenerator.generateFromSecret(totpSecret)
                    if (totpResult != null) {
                        totpCircle.visibility = View.VISIBLE
                        totpCircle.setProgress(totpResult.progressFraction, totpResult.remainingSeconds)
                    } else {
                        totpCircle.visibility = View.GONE
                    }
                } else {
                    totpCircle.visibility = View.GONE
                }
            } else {
                totpCircle.visibility = View.GONE
            }

            itemView.setOnClickListener { onEntryClicked(entry) }

            btnMore.setOnClickListener { v ->
                val context = v.context
                val popup = PopupMenu(context, v)
                popup.menu.add(0, 1, 0, "👤 Copy Username")
                popup.menu.add(0, 2, 1, "🔑 Copy Password")
                popup.menu.add(0, 3, 2, "⏱️ Copy TOTP Code")
                popup.menu.add(0, 4, 3, "✏️ Edit Entry")
                popup.menu.add(0, 5, 4, "🗑️ Delete Entry")

                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> {
                            val user = entry.username
                            if (!user.isNullOrEmpty()) {
                                copyToClipboard(context, "Username", user)
                            } else {
                                Toast.makeText(context, "No username", Toast.LENGTH_SHORT).show()
                            }
                            true
                        }
                        2 -> {
                            val pass = VaultCryptoManager.decryptToString(entry.passwordEncrypted)
                            if (!pass.isNullOrEmpty()) {
                                copyToClipboard(context, "Password", pass)
                            } else {
                                Toast.makeText(context, "No password", Toast.LENGTH_SHORT).show()
                            }
                            true
                        }
                        3 -> {
                            val totpSecret = VaultCryptoManager.decryptToString(entry.totpSecretEncrypted)
                            if (!totpSecret.isNullOrEmpty()) {
                                val res = TotpGenerator.generateFromSecret(totpSecret)
                                if (res != null) {
                                    copyToClipboard(context, "TOTP Code", res.code)
                                } else {
                                    Toast.makeText(context, "Invalid TOTP config", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "No TOTP configured", Toast.LENGTH_SHORT).show()
                            }
                            true
                        }
                        4 -> {
                            onEntryEdit(entry)
                            true
                        }
                        5 -> {
                            onEntryDelete(entry)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }

        private fun copyToClipboard(context: Context, label: String, value: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText(label, value))
            Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }
}
