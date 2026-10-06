package org.linphone.activities.main.contact.fragments

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.R
import org.linphone.activities.GenericFragment
import org.linphone.activities.main.MainActivity
import org.linphone.activities.main.contact.viewmodels.DirectoryContactEditorViewModel
import org.linphone.activities.main.contact.viewmodels.DirectoryContactEditorViewModelFactory
import org.linphone.activities.main.viewmodels.DialogViewModel
import org.linphone.databinding.DirectoryContactEditorFragmentBinding
import org.linphone.models.contact.DirectoryFieldTypes
import org.linphone.models.contact.FieldDefinitionModel
import org.linphone.models.search.UserDataModel
import org.linphone.services.DirectoriesService
import org.linphone.utils.DialogUtils
import org.linphone.utils.Log

/**
 * Adds or edits a contact in a gateway contact directory (the Personal directory, or any other the
 * user can contribute to). Arguments: DirectoryId, and ContactId to edit the contact shown in the
 * details screen, or PhoneNumber to pre-fill a new contact.
 */
class DirectoryContactEditorFragment : GenericFragment<DirectoryContactEditorFragmentBinding>() {
    companion object {
        const val ARG_DIRECTORY_ID = "DirectoryId"
        const val ARG_CONTACT_ID = "ContactId"
        const val ARG_PHONE_NUMBER = "PhoneNumber"

        private const val AVATAR_SIZE_PX = 256
    }

    private lateinit var viewModel: DirectoryContactEditorViewModel

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadAvatar(uri)
    }

    override fun getLayoutId(): Int = R.layout.directory_contact_editor_fragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner

        val directoryId = arguments?.getString(ARG_DIRECTORY_ID)
        val directory = directoryId?.let {
            DirectoriesService.getInstance(requireContext()).findDirectory(
                it
            )
        }
        if (directory == null) {
            Log.e("[Directory Contact Editor] Directory [$directoryId] not found, aborting!")
            goBack()
            return
        }

        val contactId = arguments?.getString(ARG_CONTACT_ID)
        var photoUrl: String? = null
        var isFavourite = false
        val contact = if (contactId != null) {
            val friend = sharedViewModel.selectedContact.value
            val userData = friend?.userData as? UserDataModel
            if (userData?.contact?.id != contactId) {
                Log.e(
                    "[Directory Contact Editor] Contact [$contactId] isn't the selected contact, aborting!"
                )
                goBack()
                return
            }
            photoUrl = friend.photo
            isFavourite = userData.isInFavourites
            userData.contact
        } else {
            null
        }

        viewModel = ViewModelProvider(
            this,
            DirectoryContactEditorViewModelFactory(
                directory,
                contact,
                photoUrl,
                isFavourite,
                arguments?.getString(ARG_PHONE_NUMBER)
            )
        )[DirectoryContactEditorViewModel::class.java]
        binding.viewModel = viewModel

        binding.setBackClickListener { goBack() }
        binding.setAvatarClickListener { onAvatarClicked() }
        binding.setDeleteClickListener { confirmDelete() }

        bindField(binding.nameLayout, binding.name, viewModel.nameField)
        bindField(binding.phoneLayout, binding.phone, viewModel.primaryPhoneField)

        binding.additionalFieldsTitle.visibility =
            if (viewModel.additionalFields.isEmpty()) View.GONE else View.VISIBLE
        for (field in viewModel.additionalFields) {
            val layout = TextInputLayout(requireContext())
            val editText = TextInputEditText(layout.context)
            editText.background = null
            editText.isSingleLine = true
            layout.addView(editText)
            binding.additionalFields.addView(layout)
            bindField(layout, editText, field)
        }

        viewModel.nameError.observe(viewLifecycleOwner) { error ->
            binding.nameLayout.error = if (error) getString(nameErrorMessage()) else null
        }
        viewModel.phoneError.observe(viewLifecycleOwner) { error ->
            binding.phoneLayout.error = if (error) {
                getString(
                    R.string.contact_directory_phone_required
                )
            } else {
                null
            }
        }

        viewModel.newAvatar.observe(viewLifecycleOwner) { updateAvatar() }
        viewModel.avatarUrl.observe(viewLifecycleOwner) { updateAvatar() }

        viewModel.messageEvent.observe(viewLifecycleOwner) {
            it.consume { message -> (requireActivity() as MainActivity).showSnackBar(message) }
        }

        viewModel.doneEvent.observe(viewLifecycleOwner) {
            it.consume { friend ->
                // Show the saved contact (or nothing, once deleted) when going back to the details screen
                if (viewModel.isEditing) sharedViewModel.selectedContact.value = friend
                goBack()
            }
        }
    }

    private fun nameErrorMessage() =
        if (viewModel.directory.fields.any { it.definitionType == DirectoryFieldTypes.COMPANY_NAME }) {
            R.string.contact_directory_name_or_company_required
        } else {
            R.string.contact_directory_name_required
        }

    private fun bindField(layout: TextInputLayout, editText: EditText, field: FieldDefinitionModel?) {
        if (field == null) {
            layout.visibility = View.GONE
            return
        }

        layout.hint = field.name
        editText.inputType = when (field.definitionType) {
            DirectoryFieldTypes.PHONE -> InputType.TYPE_CLASS_PHONE
            DirectoryFieldTypes.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            DirectoryFieldTypes.CONTACT_NAME -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        editText.setText(viewModel.values[field.id].orEmpty())
        editText.doAfterTextChanged { text ->
            viewModel.values[field.id] = text?.toString().orEmpty()
            layout.error = null
        }
    }

    private fun updateAvatar() {
        val newAvatar = viewModel.newAvatar.value
        val url = viewModel.avatarUrl.value
        when {
            newAvatar != null -> binding.avatar.setImageBitmap(
                BitmapFactory.decodeByteArray(newAvatar, 0, newAvatar.size)
            )
            !url.isNullOrEmpty() -> binding.avatar.load(url) {
                error(R.drawable.voip_single_contact_avatar)
            }
            else -> binding.avatar.setImageResource(R.drawable.voip_single_contact_avatar)
        }
    }

    private fun onAvatarClicked() {
        if (!viewModel.hasAvatar()) {
            launchAvatarPicker()
            return
        }

        AlertDialog.Builder(requireContext())
            .setItems(
                arrayOf(
                    getString(R.string.contact_directory_change_avatar),
                    getString(R.string.contact_directory_remove_avatar)
                )
            ) { _, which ->
                if (which == 0) launchAvatarPicker() else viewModel.removeAvatar()
            }
            .show()
    }

    private fun launchAvatarPicker() {
        pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // Crops the picked image to a centred square and scales it down before uploading it as a PNG.
    private fun loadAvatar(uri: Uri) {
        val resolver = requireContext().contentResolver
        viewLifecycleOwner.lifecycleScope.launch {
            val png = withContext(Dispatchers.IO) {
                try {
                    val source = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: return@withContext null
                    val side = minOf(source.width, source.height)
                    val square = Bitmap.createBitmap(
                        source,
                        (source.width - side) / 2,
                        (source.height - side) / 2,
                        side,
                        side
                    )
                    val scaled = Bitmap.createScaledBitmap(
                        square,
                        minOf(side, AVATAR_SIZE_PX),
                        minOf(side, AVATAR_SIZE_PX),
                        true
                    )
                    ByteArrayOutputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    }
                } catch (e: Exception) {
                    Log.e("[Directory Contact Editor] Failed to read picked image", e)
                    null
                }
            }
            if (png != null) {
                viewModel.setAvatar(png)
            } else {
                (requireActivity() as MainActivity).showSnackBar(
                    R.string.contact_directory_avatar_failed
                )
            }
        }
    }

    private fun confirmDelete() {
        val dialogViewModel = DialogViewModel(getString(R.string.contact_directory_delete_dialog))
        dialogViewModel.showIcon = true
        dialogViewModel.iconResource = R.drawable.dialog_delete_icon
        val dialog: Dialog = DialogUtils.getDialog(requireContext(), dialogViewModel)

        dialogViewModel.showCancelButton {
            dialog.dismiss()
        }

        dialogViewModel.showDeleteButton(
            {
                viewModel.delete()
                dialog.dismiss()
            },
            getString(R.string.dialog_delete)
        )

        dialog.show()
    }
}
