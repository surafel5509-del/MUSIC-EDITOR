package com.studioone.mobile.feature.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.domain.EntitlementRepository
import com.studioone.mobile.core.model.Product
import com.studioone.mobile.core.model.PurchaseState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PaywallUiState(
    val products: List<Product> = emptyList(),
    val selectedProductId: String? = null,
    val isPurchasing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val entitlementRepository: EntitlementRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PaywallUiState())
    val uiState: StateFlow<PaywallUiState> = _uiState.asStateFlow()

    fun loadProducts() {
        viewModelScope.launch {
            when (val result = entitlementRepository.products()) {
                is DataResult.Success -> _uiState.value = _uiState.value.copy(
                    products = result.data,
                    selectedProductId = result.data.firstOrNull { it.productId.endsWith("annual") }?.productId,
                )
                is DataResult.Failure -> _uiState.value = _uiState.value.copy(message = result.error.message)
                DataResult.Loading -> Unit
            }
        }
    }

    fun selectProduct(id: String) { _uiState.value = _uiState.value.copy(selectedProductId = id) }

    fun purchase(onSuccess: () -> Unit) {
        val id = _uiState.value.selectedProductId ?: return
        _uiState.value = _uiState.value.copy(isPurchasing = true)
        viewModelScope.launch {
            when (val result = entitlementRepository.purchase(id)) {
                is DataResult.Success -> {
                    _uiState.value = _uiState.value.copy(isPurchasing = false)
                    if (result.data == PurchaseState.PURCHASED) onSuccess()
                    // PENDING: verification completes via the billing listener;
                    // entitlement flow updates the UI globally.
                }
                is DataResult.Failure -> _uiState.value = _uiState.value.copy(
                    isPurchasing = false, message = result.error.message)
                DataResult.Loading -> Unit
            }
        }
    }
}
