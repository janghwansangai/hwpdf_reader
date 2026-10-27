package com.fastreader.app.core

/** 사용자에게 그대로 보여줄 수 있는 메시지를 가진 문서 오류. */
open class DocumentException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UnsupportedDocumentException(message: String) : DocumentException(message)

class EncryptedDocumentException(message: String) : DocumentException(message)

class CorruptDocumentException(message: String, cause: Throwable? = null) : DocumentException(message, cause)
