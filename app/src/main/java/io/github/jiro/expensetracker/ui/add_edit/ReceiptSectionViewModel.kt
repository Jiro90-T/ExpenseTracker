package io.github.jiro.expensetracker.ui.add_edit

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import javax.inject.Inject

@HiltViewModel
class ReceiptSectionViewModel @Inject constructor(
    val receiptRepository: ReceiptRepository,
) : ViewModel()
