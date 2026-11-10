package com.example

import kotlin.collections.List

class Greeter(val names:List<String>){
    fun greet() : String { return names.joinToString(separator = ", ", prefix = "Hello ", postfix = "!") { it.trim() } }
  companion object { const val DEFAULT="world" }
}
