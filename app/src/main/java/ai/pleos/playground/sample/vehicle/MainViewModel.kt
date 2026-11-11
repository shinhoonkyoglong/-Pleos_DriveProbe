package ai.pleos.playground.sample.vehicle

import ai.pleos.playground.sample.vehicle.explorer.VehicleDomain
import ai.pleos.playground.sample.vehicle.explorer.VehicleApiExplorer
import ai.pleos.playground.sample.vehicle.explorer.ApiFeatureDescriptor
import ai.pleos.playground.sample.vehicle.explorer.ApiMethodDescriptor
import ai.pleos.playground.sample.vehicle.explorer.ApiMethodStatus
import ai.pleos.playground.sample.vehicle.explorer.ApiExecutionMode
import ai.pleos.playground.sample.vehicle.explorer.ApiOperation
import ai.pleos.playground.sample.vehicle.explorer.ApiNumericRange
import ai.pleos.playground.sample.vehicle.explorer.apiFeatures
import ai.pleos.playground.sample.vehicle.explorer.formatApiResponse
import ai.pleos.playground.sample.vehicle.explorer.formatFeatureDisplay
import ai.pleos.playground.sample.vehicle.explorer.requestValues
import ai.pleos.playground.sample.vehicle.ui.data.AppPage
import ai.pleos.playground.sample.vehicle.ui.data.ApiExplorerPane
import ai.pleos.playground.sample.vehicle.ui.data.FeatureRequestFlow
import ai.pleos.playground.sample.vehicle.ui.data.MainUiState
import ai.pleos.playground.sample.vehicle.ui.data.withCapabilityRanges
import ai.pleos.playground.sample.vehicle.ui.data.withFeatureResult
import ai.pleos.playground.sample.vehicle.vehicle.VehicleClient
import ai.pleos.playground.sample.vehicle.vehicle.VehicleClient.DoorOperation
import ai.pleos.playground.sample.vehicle.vehicle.RuntimeVersionProvider
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainViewModel(application: Application) :
    AndroidViewModel(application),
    VehicleClient.Callback,
    VehicleApiExplorer.Callback {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()

    private val vehicleClient = VehicleClient(application, this)
    private val apiExplorer = VehicleApiExplorer(
        session = vehicleClient,
        callback = this
    )
    private var activeTypedMethodId: String? = null
    private val pendingApiValues = mutableMapOf<String, Map<String, String>>()
    private val featureRequestFlow = FeatureRequestFlow()

    init {
        val featureCounts = VehicleDomain.entries.associateWith { domain ->
            apiFeatures(domain, apiExplorer.methods(domain)).size
        }
        _uiState.update {
            it.copy(
                runtimeVersions = RuntimeVersionProvider(application).read(),
                domainFeatureCounts = featureCounts
            )
        }
        if (vehicleClient.initialize()) {
            _uiState.update {
                it.copy(
                    isInitialized = true
                )
            }
            showApiExplorer(VehicleDomain.DOOR)
            // 작업 #25: API 탐색 상태는 위에서 준비만 하고, 첫 화면은 홈으로 둔다
            showHome()
        }
    }

    fun showHome() {
        _uiState.update {
            it.copy(
                page = AppPage.HOME,
                featureSearchQuery = "",
                errorMessage = null,
                featureErrorMessage = null
            )
        }
    }

    fun showApiExplorer(domain: VehicleDomain) {
        if (_uiState.value.isSubscribed) {
            vehicleClient.stopDoorPositionUpdates()
        }
        apiExplorer.release()
        pendingApiValues.clear()
        activeTypedMethodId = null
        featureRequestFlow.clear()
        val methods = apiExplorer.methods(domain)
        val features = apiFeatures(domain, methods)
        _uiState.update {
            it.copy(
                page = AppPage.API_EXPLORER,
                selectedDomain = domain,
                apiExplorerPane = ApiExplorerPane.FEATURE_LIST,
                apiMethods = methods,
                apiFeatures = features,
                featureSearchQuery = "",
                selectedFeatureId = null,
                focusedMethodId = null,
                apiFocusSequence = 0,
                explorerEvents = emptyList(),
                apiMethodStatuses = emptyMap(),
                apiMethodResponses = emptyMap(),
                featureCurrentValues = emptyMap(),
                featureCurrentDetails = emptyMap(),
                featureValueRanges = emptyMap(),
                isSubscribed = false,
                subscribedFeatureId = null,
                capabilityAccess = null,
                currentPosition = null,
                errorMessage = null,
                featureErrorMessage = null
            )
        }
    }

    /** 2번 '출발 전' 운전자 조건 설정 화면 열기(작업 #17). API 탐색 상태는 그대로 둔다 */
    fun showDriverConditionSettings() {
        _uiState.update { it.copy(page = AppPage.DRIVER_CONDITION_SETTINGS) }
    }

    /** 운전자 조건 설정 화면에서 돌아가기 — 진입점이 홈 하나뿐이라 홈으로(작업 #25) */
    fun closeDriverConditionSettings() {
        showHome()
    }

    /** 홈 카드 → 2번 '출발 전' 확인 전체 화면(작업 #25) */
    fun showDepartureDetail() {
        _uiState.update { it.copy(page = AppPage.DEPARTURE_DETAIL) }
    }

    /** API 탐색기로(홈의 개발용 버튼). 이미 준비된 탐색 상태를 그대로 보여 준다(작업 #25) */
    fun openApiExplorer() {
        _uiState.update { it.copy(page = AppPage.API_EXPLORER) }
    }

    fun selectFeature(featureId: String) {
        val feature = _uiState.value.apiFeatures.firstOrNull { it.id == featureId } ?: return
        featureRequestFlow.beginEntry(featureId)
        _uiState.update {
            it.copy(
                page = AppPage.API_EXPLORER,
                apiExplorerPane = ApiExplorerPane.FEATURE_DASHBOARD,
                selectedFeatureId = featureId,
                focusedMethodId = null,
                capabilityAccess = null,
                errorMessage = null,
                featureErrorMessage = null
            )
        }
        if (!runFeatureCapability(feature)) {
            featureRequestFlow.shouldRunAutomaticRead(
                featureId = featureId,
                operation = ApiOperation.CAPABILITY,
                succeeded = false
            )
        }
    }

    fun searchFeatures(query: String) {
        _uiState.update { it.copy(featureSearchQuery = query) }
    }

    fun backToFeatureList() {
        featureRequestFlow.clear()
        _uiState.update {
            it.copy(
                page = AppPage.API_EXPLORER,
                apiExplorerPane = ApiExplorerPane.FEATURE_LIST,
                selectedFeatureId = null,
                errorMessage = null,
                featureErrorMessage = null
            )
        }
    }

    fun runFeatureCapability() {
        _uiState.value.apiFeatures
            .firstOrNull { it.id == _uiState.value.selectedFeatureId }
            ?.let(::runFeatureCapability)
    }

    fun runFeatureRead(selectedValues: Map<String, String>) {
        val feature = selectedFeature() ?: return
        val method = feature.read
        if (method == null) {
            publishFeatureError("No read API is available for ${feature.title}.")
            return
        }
        invokeApi(method.id, requestValues(method, selectedValues))
    }

    fun probeTemperatureRead() {
        val feature = selectedFeature() ?: return
        if (feature.read?.name != "getHvacTemperature") return
        try {
            android.util.Log.i("DriveProbe", "temperature read requested with default parameters")
            runFeatureRead(emptyMap())
        } catch (e: Exception) {
            android.util.Log.e("DriveProbe", "temperature read request failed", e)
        }
    }

    fun probeTemperatureSet() {
        val feature = selectedFeature() ?: return
        val method = feature.write ?: return
        if (method.name != "setHvacTemperature") return
        val numeric = method.parameters.firstOrNull { it.kind == ai.pleos.playground.sample.vehicle.explorer.ApiParameterKind.FLOAT } ?: return
        android.util.Log.i("DriveProbe", "SET_TEST requested 25.0 param=${numeric.name}")
        runFeatureSet(mapOf(numeric.name to "25.0"))
    }

    // Manual ADB commands only. This diagnostic does not infer driving state.
    fun runManualProbe(kind: String, step: String, value: String?) {
        val feature = selectedFeature()
        val read = feature?.read
        val write = feature?.write
        val validFeature = when (kind) {
            "fan" -> read?.name?.contains("HvacFanSpeed", true) == true &&
                write?.name?.contains("HvacFanSpeed", true) == true
            "window" -> read?.name?.contains("WindowPosition", true) == true &&
                write?.name?.contains("WindowPosition", true) == true
            else -> false
        }
        if (!validFeature) {
            android.util.Log.e("DriveProbe", "PROBE rejected kind=$kind step=$step: select matching fan speed/window position feature (read=${read?.name}, write=${write?.name})")
            return
        }
        val method = when (step) {
            "before", "after" -> read!!
            "set" -> write!!
            else -> {
                android.util.Log.e("DriveProbe", "PROBE rejected: step must be before, set, or after")
                return
            }
        }
        val area = if (kind == "fan") "FRONT_BOTH" else "ROW_1_LEFT"
        val areaParameter = method.parameters.singleOrNull { it.kind == ai.pleos.playground.sample.vehicle.explorer.ApiParameterKind.AREA }
        if (areaParameter == null || area !in areaParameter.options) {
            android.util.Log.e("DriveProbe", "PROBE rejected: required area $area unavailable for ${method.name}")
            return
        }
        val values = mutableMapOf(areaParameter.name to area)
        if (step == "set") {
            val numeric = method.parameters.singleOrNull { it.kind == ai.pleos.playground.sample.vehicle.explorer.ApiParameterKind.INTEGER }
            if (numeric == null || value?.toIntOrNull() == null) {
                android.util.Log.e("DriveProbe", "PROBE rejected: integer value and single integer parameter required")
                return
            }
            values[numeric.name] = value
        }
        android.util.Log.i("DriveProbe", "PROBE REQUEST kind=$kind step=$step area=$area value=${value ?: "-"} method=${method.name}")
        if (!invokeApi(method.id, requestValues(method, values))) {
            android.util.Log.e("DriveProbe", "PROBE dispatch failed kind=$kind step=$step")
        }
    }

    fun runFeatureSet(values: Map<String, String>) {
        val feature = selectedFeature() ?: return
        val method = feature.write
        if (method == null) {
            publishFeatureError("No set API is available for ${feature.title}.")
            return
        }
        invokeApi(method.id, requestValues(method, values))
    }

    fun startFeatureUpdates(values: Map<String, String>) {
        val feature = selectedFeature() ?: return
        val method = feature.register
        if (method == null) {
            publishFeatureError("No register API is available for ${feature.title}.")
            return
        }
        invokeApi(method.id, requestValues(method, values))
    }

    fun stopFeatureUpdates(values: Map<String, String>) {
        val feature = selectedFeature() ?: return
        val method = feature.unregister
        if (method == null) {
            publishFeatureError("No unregister API is available for ${feature.title}.")
            return
        }
        invokeApi(method.id, requestValues(method, values))
    }

    private fun selectedFeature(): ApiFeatureDescriptor? =
        _uiState.value.apiFeatures.firstOrNull {
            it.id == _uiState.value.selectedFeatureId
        }

    private fun runFeatureCapability(feature: ApiFeatureDescriptor): Boolean {
        _uiState.update { it.withCapabilityRanges(feature.id, emptyMap()) }
        val method = feature.capability
        if (method == null) {
            _uiState.update {
                it.copy(featureCurrentValues = it.featureCurrentValues +
                    (feature.id to "No capability API declared"))
            }
            return false
        }
        return invokeApi(method.id, defaultValues(method))
    }

    private fun defaultValues(method: ApiMethodDescriptor): Map<String, String> =
        requestValues(method, emptyMap())

    private fun invokeApi(methodId: String, values: Map<String, String>): Boolean {
        val domain = _uiState.value.selectedDomain ?: return false
        val method = _uiState.value.apiMethods.firstOrNull { it.id == methodId } ?: return false
        return try {
            if (method.executionMode == ApiExecutionMode.TYPED_REFERENCE) {
                pendingApiValues.remove(methodId)
                invokeTypedDoorApi(method.name, methodId, values)
            } else {
                pendingApiValues[methodId] = values
                focusApiMethod(methodId)
                markApiMethod(methodId, ApiMethodStatus.RUNNING)
                apiExplorer.invoke(domain, methodId, values)
                true
            }
        } catch (exception: Exception) {
            pendingApiValues.remove(methodId)
            if (method.executionMode == ApiExecutionMode.TYPED_REFERENCE) {
                onError(exception)
            } else {
                onExplorerEvent(
                    message = "${method.name} failed: ${exception.message ?: exception.javaClass.simpleName}",
                    isError = true,
                    methodId = methodId,
                    isResult = true,
                    numericRanges = null,
                    isValueUpdate = false
                )
            }
            false
        }
    }

    private fun focusApiMethod(methodId: String) {
        _uiState.update {
            it.copy(
                focusedMethodId = methodId,
                apiFocusSequence = it.apiFocusSequence + 1
            )
        }
    }

    fun dismissError() {
        _uiState.update {
            it.copy(
                errorMessage = null,
                featureErrorMessage = null
            )
        }
    }

    override fun onCapabilities(
        accessType: String,
        ranges: Map<String, ApiNumericRange>
    ) {
        val methodId = activeTypedMethodId
        val feature = methodId?.let(::featureForMethod)
        val supported = accessType != "NONE"
        markActiveTypedMethod(if (supported) ApiMethodStatus.SUPPORTED else ApiMethodStatus.UNSUPPORTED)
        updateApiMethodResponse(methodId, "Capability: $accessType, ranges $ranges")
        _uiState.update { current ->
            val updated = feature?.let {
                current.withCapabilityRanges(it.id, if (supported) ranges else emptyMap())
            } ?: current
            updated.copy(
                capabilityAccess = accessType,
                errorMessage = null,
                featureErrorMessage = null
            )
        }
        feature?.let { completeFeatureOperation(it, ApiOperation.CAPABILITY, succeeded = supported) }
    }

    override fun onDoorPositionRead(position: Int?) {
        val methodId = activeTypedMethodId
        markActiveTypedMethod(ApiMethodStatus.RESULT)
        updateApiMethodResponse(methodId, "Door position read: ${position ?: "unavailable"}")
        updateFeatureValue(
            methodName = "getCurrentDoorPosition",
            value = position?.toString() ?: "null"
        )
        _uiState.update {
            it.copy(
                currentPosition = position,
                errorMessage = null,
                featureErrorMessage = null
            )
        }
    }

    override fun onDoorPositionSet(position: Int?) {
        val methodId = activeTypedMethodId
        val feature = methodId?.let(::featureForMethod)
        markActiveTypedMethod(ApiMethodStatus.RESULT)
        updateApiMethodResponse(methodId, "Door position set: ${position ?: "accepted"}")
        _uiState.update {
            it.copy(
                errorMessage = null,
                featureErrorMessage = null
            )
        }
        feature?.let { completeFeatureOperation(it, ApiOperation.SET, succeeded = true) }
    }

    override fun onDoorPositionChanged(position: Int?) {
        val methodId = _uiState.value.apiMethods
            .firstOrNull { it.name == "registerDoorPosition" }
            ?.id
        methodId?.let { markApiMethod(it, ApiMethodStatus.RESULT) }
        updateApiMethodResponse(methodId, "Door update: ${position ?: "unavailable"}")
        updateFeatureValue(
            methodName = "registerDoorPosition",
            value = position?.toString() ?: "null"
        )
        _uiState.update {
            it.copy(
                currentPosition = position,
                errorMessage = null,
                featureErrorMessage = null
            )
        }
    }

    override fun onExplorerEvent(
        message: String,
        isError: Boolean,
        methodId: String?,
        isResult: Boolean,
        numericRanges: Map<String, ApiNumericRange>?,
        isValueUpdate: Boolean
    ) {
        if (methodId != null && (methodId.contains("HvacFanSpeed", true) || methodId.contains("WindowPosition", true))) {
            android.util.Log.i("DriveProbe", "PROBE EVENT method=$methodId result=$isResult error=$isError: $message")
        }
        if (methodId?.contains("HvacTemperature", ignoreCase = true) == true) {
            android.util.Log.i("DriveProbe", "temperature callback result=$isResult error=$isError: $message")
        }
        val method = methodId?.let { id ->
            _uiState.value.apiMethods.firstOrNull { it.id == id }
        }
        val feature = methodId?.let(::featureForMethod)
        val methodOperation = methodId?.let { id ->
            _uiState.value.apiMethods.firstOrNull { it.id == id }?.operation
        }
        val requestValues = if (isResult && methodId != null) {
            pendingApiValues.remove(methodId).orEmpty()
        } else {
            methodId?.let { pendingApiValues[it] }.orEmpty()
        }
        val displayValue = if (feature != null && method != null) {
            formatFeatureDisplay(
                featureTitle = feature.title,
                message = message,
                requestValues = requestValues
            )
        } else {
            null
        }
        val displayMessage = if (feature != null && method != null) {
            formatApiResponse(
                featureTitle = feature.title,
                methodName = method.name,
                message = message,
                requestValues = requestValues
            )
        } else {
            message
        }
        methodId?.let { id ->
            val status = when {
                !isResult -> ApiMethodStatus.RUNNING
                isError && method?.operation == ApiOperation.CAPABILITY -> ApiMethodStatus.UNSUPPORTED
                isError -> ApiMethodStatus.FAILED
                method?.operation == ApiOperation.CAPABILITY -> ApiMethodStatus.SUPPORTED
                else -> ApiMethodStatus.RESULT
            }
            markApiMethod(id, status)
            if (isResult) {
                updateApiMethodResponse(id, displayMessage)
            }
            if (isResult && feature != null && method?.operation == ApiOperation.CAPABILITY) {
                _uiState.update {
                    it.withCapabilityRanges(feature.id, if (isError) emptyMap() else numericRanges.orEmpty())
                }
            }
            if (isResult && !isError && feature != null && method != null &&
                method.operation != ApiOperation.CAPABILITY
            ) {
                _uiState.update { current ->
                    val updated = if (isValueUpdate) {
                        current.withFeatureResult(feature.id, method.operation, displayValue!!.value, displayValue.detail)
                    } else {
                        current
                    }
                    updated.copy(
                        isSubscribed = when (method.operation) {
                            ApiOperation.REGISTER -> true
                            ApiOperation.UNREGISTER -> false
                            else -> current.isSubscribed
                        },
                        subscribedFeatureId = when (method.operation) {
                            ApiOperation.REGISTER -> feature.id
                            ApiOperation.UNREGISTER -> null
                            else -> current.subscribedFeatureId
                        }
                    )
                }
            }
        }
        _uiState.update {
            it.copy(
                explorerEvents = it.explorerEvents.prepend(event(displayMessage)),
                errorMessage = null,
                featureErrorMessage = when {
                    !isError || methodOperation == ApiOperation.CAPABILITY -> null
                    else -> message
                }
            )
        }
        if (isResult && feature != null && method != null) {
            completeFeatureOperation(feature, method.operation, succeeded = !isError)
        }
    }

    override fun onError(exception: Exception) {
        val methodId = activeTypedMethodId
        val method = methodId?.let { id -> _uiState.value.apiMethods.firstOrNull { it.id == id } }
        val feature = methodId?.let(::featureForMethod)
        markActiveTypedMethod(ApiMethodStatus.FAILED)
        updateApiMethodResponse(methodId, "Error: ${exception.message ?: exception.javaClass.simpleName}")
        publishError(exception)
        if (feature != null && method != null) {
            if (method.operation == ApiOperation.CAPABILITY) {
                _uiState.update { it.withCapabilityRanges(feature.id, emptyMap()) }
            }
            completeFeatureOperation(feature, method.operation, succeeded = false)
        }
    }

    override fun onDoorOperationError(operation: DoorOperation, exception: Exception) {
        val methodId = activeTypedMethodId
        val method = methodId?.let { id -> _uiState.value.apiMethods.firstOrNull { it.id == id } }
        val feature = methodId?.let(::featureForMethod)
        markActiveTypedMethod(
            if (operation == DoorOperation.CAPABILITY) {
                ApiMethodStatus.UNSUPPORTED
            } else {
                ApiMethodStatus.FAILED
            }
        )
        updateApiMethodResponse(
            methodId,
            "Error: ${exception.message ?: exception.javaClass.simpleName}"
        )
        publishError(exception)
        if (feature != null && method != null) {
            if (operation == DoorOperation.CAPABILITY) {
                _uiState.update { it.withCapabilityRanges(feature.id, emptyMap()) }
            }
            completeFeatureOperation(feature, method.operation, succeeded = false)
        }
    }

    private fun publishError(exception: Exception) {
        val message = exception.message ?: exception.javaClass.simpleName
        _uiState.update {
            it.copy(
                errorMessage = message,
                featureErrorMessage = null
            )
        }
    }

    private fun publishFeatureError(message: String) {
        _uiState.update {
            it.copy(
                errorMessage = null,
                featureErrorMessage = message,
                explorerEvents = it.explorerEvents.prepend(event(message))
            )
        }
    }

    override fun onCleared() {
        apiExplorer.release()
        vehicleClient.release()
        super.onCleared()
    }

    private fun event(message: String): String =
        "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $message"

    private fun invokeTypedDoorApi(
        methodName: String,
        methodId: String,
        values: Map<String, String>
    ): Boolean {
        if (!beginTypedMethod(methodId)) return false
        focusApiMethod(methodId)
        markApiMethod(methodId, ApiMethodStatus.RUNNING)
        when (methodName) {
            "checkDoorPositionCapabilities" -> vehicleClient.inspectDoorPositionCapabilities()
            "getCurrentDoorPosition" -> vehicleClient.readDoorPosition(values.getValue("area"))
            "setDoorPosition" -> {
                val position = values["value"]?.toIntOrNull()
                if (position == null) {
                    onError(IllegalArgumentException("Door position must be an integer."))
                    return false
                } else {
                    vehicleClient.setDoorPosition(values.getValue("area"), position)
                }
            }
            "registerDoorPosition" -> {
                if (vehicleClient.startDoorPositionUpdates(values.getValue("area"))) {
                    markActiveTypedMethod(ApiMethodStatus.RESULT)
                    updateApiMethodResponse(methodId, "Door updates registered")
                    _uiState.update {
                        it.copy(
                            isSubscribed = true,
                            subscribedFeatureId = featureIdForMethod(methodName)
                        )
                    }
                } else {
                    markActiveTypedMethod(ApiMethodStatus.FAILED)
                    updateApiMethodResponse(methodId, "Door updates could not be registered")
                    return false
                }
            }
            "unregisterDoorPosition" -> {
                if (vehicleClient.stopDoorPositionUpdates(values.getValue("area"))) {
                    markActiveTypedMethod(ApiMethodStatus.RESULT)
                    updateApiMethodResponse(methodId, "Door updates unregistered")
                    _uiState.update {
                        it.copy(isSubscribed = false, subscribedFeatureId = null)
                    }
                } else {
                    markActiveTypedMethod(ApiMethodStatus.FAILED)
                    updateApiMethodResponse(methodId, "Door updates could not be unregistered")
                    return false
                }
            }
            else -> error("No typed sample is registered for $methodName")
        }
        return true
    }

    private fun completeFeatureOperation(
        feature: ApiFeatureDescriptor,
        operation: ApiOperation,
        succeeded: Boolean
    ) {
        val read = feature.read
        if (featureRequestFlow.shouldRunAutomaticRead(feature.id, operation, succeeded) &&
            read != null
        ) {
            invokeApi(read.id, defaultValues(read))
        }
    }

    private fun beginTypedMethod(methodId: String?): Boolean {
        if (methodId == null) {
            publishError(IllegalStateException("Door API metadata is unavailable."))
            return false
        }
        if (activeTypedMethodId != null) {
            publishError(IllegalStateException("Wait for the current Door API call to finish."))
            return false
        }
        activeTypedMethodId = methodId
        return true
    }

    private fun markActiveTypedMethod(status: ApiMethodStatus) {
        activeTypedMethodId?.let { methodId ->
            markApiMethod(methodId, status)
            activeTypedMethodId = null
        }
    }

    private fun markApiMethod(methodId: String, status: ApiMethodStatus) {
        _uiState.update {
            it.copy(apiMethodStatuses = it.apiMethodStatuses + (methodId to status))
        }
    }

    private fun featureForMethod(methodId: String): ApiFeatureDescriptor? =
        _uiState.value.apiFeatures.firstOrNull { feature ->
            feature.methods.any { it.id == methodId }
        }

    private fun featureIdForMethod(methodName: String): String? =
        _uiState.value.apiMethods
            .firstOrNull { it.name == methodName }
            ?.let { featureForMethod(it.id)?.id }

    private fun updateFeatureValue(methodName: String, value: String) {
        val method = _uiState.value.apiMethods.firstOrNull { it.name == methodName } ?: return
        val feature = featureForMethod(method.id) ?: return
        _uiState.update { it.withFeatureResult(feature.id, method.operation, value, null) }
    }

    private fun updateApiMethodResponse(methodId: String?, message: String) {
        methodId ?: return
        _uiState.update {
            it.copy(apiMethodResponses = it.apiMethodResponses + (methodId to event(message)))
        }
    }

    private fun List<String>.prepend(value: String): List<String> =
        (listOf(value) + this).take(MAX_EVENT_COUNT)

    companion object {
        private const val MAX_EVENT_COUNT = 12
    }
}
