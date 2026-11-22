package com.maxrave.simpmusic.viewModel

import com.eygraber.uri.Uri
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.CommonRepository
import com.maxrave.simpmusic.expect.getDownloadFolderPath

actual suspend fun calculateDataFraction(cacheRepository: CacheRepository): SettingsStorageSectionFraction? = null

actual suspend fun restoreNative(
    commonRepository: CommonRepository,
    uri: Uri,
    getData: () -> Unit,
) {}

actual suspend fun readPickedFile(uri: Uri): ByteArray = ByteArray(0)

actual suspend fun backupNative(
    commonRepository: CommonRepository,
    uri: Uri,
    backupDownloaded: Boolean,
) {}

actual fun getPackageName(): String = "com.maxrave.simpmusic"

actual fun getFileDir(): String = getDownloadFolderPath()

actual fun changeLanguageNative(code: String) {}
