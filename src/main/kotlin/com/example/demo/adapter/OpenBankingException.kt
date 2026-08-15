package com.example.demo.adapter

/** 오픈뱅킹 호출 실패. 데모에서는 `failFast=true` 로 주입한다. */
class OpenBankingException(message: String) : RuntimeException(message)
