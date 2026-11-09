package com.smartplug.app.domain.engine

import com.smartplug.app.domain.model.Automation
import com.smartplug.app.domain.model.DaysOfWeek
import com.smartplug.app.domain.model.SpecificDate
import com.smartplug.app.domain.model.TimeAfter
import com.smartplug.app.domain.model.TimeBefore
import com.smartplug.app.domain.model.TimeBetween
import com.smartplug.app.domain.model.TimeEquals
import com.smartplug.app.domain.model.Weekdays
import com.smartplug.app.domain.model.Weekend
import java.time.ZonedDateTime

/** Próximo instante em que alguma condição de horário pode mudar de valor. */
object TimeBoundaries {

    fun next(automations: List<Automation>, now: ZonedDateTime): Long? {
        val candidates = mutableListOf<ZonedDateTime>()

        fun atMinute(minute: Int): ZonedDateTime {
            val m = ((minute % 1440) + 1440) % 1440
            val today = now.toLocalDate().atStartOfDay(now.zone).plusMinutes(m.toLong())
            return if (today.isAfter(now)) today
            else now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusMinutes(m.toLong())
        }

        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)

        for (leaf in automations.flatMap { it.leaves() }) {
            when (val c = leaf.condition) {
                is TimeAfter -> candidates += atMinute(c.minuteOfDay)
                is TimeBefore -> candidates += atMinute(c.minuteOfDay)
                is TimeBetween -> {
                    candidates += atMinute(c.fromMinute)
                    candidates += atMinute(c.toMinute)
                }
                is TimeEquals -> {
                    candidates += atMinute(c.minuteOfDay)
                    candidates += atMinute(c.minuteOfDay + c.toleranceMinutes)
                }
                is DaysOfWeek, Weekdays, Weekend, is SpecificDate -> candidates += nextMidnight
                else -> Unit
            }
        }
        return candidates.minOfOrNull { it.toInstant().toEpochMilli() }
    }
}
