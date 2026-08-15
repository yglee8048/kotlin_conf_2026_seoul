package com.example.demo.adapter

import com.example.demo.domain.UserEvent
import com.example.demo.vo.UserId
import org.springframework.stereotype.Component

@Component
class UserLogRepository {
    fun saveEvent(userId: UserId, userEvent: UserEvent) {
        println("save!")
    }
}
