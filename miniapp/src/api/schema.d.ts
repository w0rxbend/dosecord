// GENERATED FILE: do not edit by hand. Source: api/openapi.json.
// Regenerate with: pnpm --dir miniapp run api:generate

export interface paths {
    "/api/v1/session": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Exchanges Telegram launch data for a short-lived session. No cookie is set. */
        post: operations["createSession"];
        delete: operations["deleteSession"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/session/keepalive": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["keepSessionAlive"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getSettings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** @description Replaces the editable set; a new time zone moves the schedules that follow the account. */
        patch: operations["updateSettings"];
        trace?: never;
    };
    "/api/v1/time-zones": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listTimeZones"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/time-zone-previews": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description A pure evaluation of a zone change; nothing is written. */
        post: operations["previewTimeZone"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/consents": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listConsents"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/consents/{purpose}/withdrawals": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Needs a confirmation in the chat (step-up). Until the step-up exists it answers stepup_required. */
        post: operations["withdrawConsent"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/today": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getToday"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/days/{date}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getDay"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/medication-options": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMedicationOptions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/medications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listMedications"];
        put?: never;
        post: operations["createMedication"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/medications/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMedication"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** @description Replaces the editable set; an absent optional member clears the value. */
        patch: operations["updateMedication"];
        trace?: never;
    };
    "/api/v1/medications/{id}/state-changes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["changeMedicationState"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/schedule-options": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getScheduleOptions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/schedule-previews": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description A pure evaluation of an unsaved draft over the next seven days; nothing is written. */
        post: operations["previewSchedule"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/medications/{id}/schedules": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listSchedules"];
        put?: never;
        post: operations["createSchedule"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/schedules/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getSchedule"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/schedules/{id}/revisions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description An edit is a new revision on the revision named by If-Match. */
        post: operations["reviseSchedule"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/schedules/{id}/state-changes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["changeScheduleState"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notification-settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getNotificationSettings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch: operations["updateNotificationSettings"];
        trace?: never;
    };
    "/api/v1/hydration/entries": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listHydrationEntries"];
        put?: never;
        post: operations["createHydrationEntry"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/hydration/entries/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** @description Voids the entry and answers with its day; a second delete answers the same. */
        delete: operations["deleteHydrationEntry"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/hydration/settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getHydrationSettings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch: operations["updateHydrationSettings"];
        trace?: never;
    };
    "/api/v1/hydration/summary": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getHydrationSummary"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/mood/scale": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMoodScale"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/mood/blob-states": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description The global geometry of the mood blob; it holds no user data. */
        get: operations["getMoodBlobStates"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/mood/checkins": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listMoodCheckins"];
        put?: never;
        post: operations["createMoodCheckin"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/mood/checkins/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** @description Voids the check-in and answers with its day; a second delete answers the same. */
        delete: operations["deleteMoodCheckin"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/mood/summary": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMoodSummary"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sleep/episodes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listSleepEpisodes"];
        put?: never;
        /** @description A night or a nap entered after the fact. */
        post: operations["createSleepEpisode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sleep/episodes/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** @description Voids the episode and answers where sleep stands; a second delete answers the same. */
        delete: operations["deleteSleepEpisode"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sleep/checkins": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Going to sleep or I'm up, now. */
        post: operations["createSleepCheckin"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sleep/settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getSleepSettings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch: operations["updateSleepSettings"];
        trace?: never;
    };
    "/api/v1/sleep/summary": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getSleepSummary"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/insights/calendar": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getInsightCalendar"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/insights/overview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description The statistics of a period, one block per domain whose consent is active. */
        get: operations["getInsightsOverview"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/insights/month": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description One calendar month: one block per domain whose consent is active, and each medication's counts. */
        get: operations["getInsightMonth"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/insights/correlations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description The patterns of the last 28 days that passed every check, and those close to their data. */
        get: operations["listInsightCorrelations"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reminders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getReminders"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reminders/presets": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Every preset with its next times for the user's current day; reading saves nothing. */
        get: operations["listReminderPresets"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reminders/plans": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Turns a preset on, replacing the plan of its kind. */
        post: operations["createReminderPlan"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reminders/plans/{kind}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** @description Turns the plan of a kind off; a second delete answers the same. */
        delete: operations["deleteReminderPlan"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reminders/pause": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Pauses every prompt for 1 to 24 hours; 0 resumes. Medication reminders are not affected. */
        post: operations["createReminderPause"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reminders/day": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getDayProfile"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch: operations["updateDayProfile"];
        trace?: never;
    };
    "/api/v1/reminders/timeline": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getReminderTimeline"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/meals": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listMeals"];
        put?: never;
        /** @description A meal; with `supersedes`, a change of a recorded meal, which the new one replaces. */
        post: operations["createMeal"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/meals/recent": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listRecentMeals"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/meals/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMeal"];
        put?: never;
        post?: never;
        /** @description Voids the meal and answers with its day; a second delete answers the same. */
        delete: operations["deleteMeal"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/nutrition/summary": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getNutritionSummary"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/nutrition/settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getNutritionSettings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch: operations["updateNutritionSettings"];
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        /** BlobColours */
        BlobColours: {
            fillInner: string;
            fillOuter: string;
            outline: string;
            ground: string;
            background: string;
        };
        /** BlobFace */
        BlobFace: {
            ink: string;
            eyes?: number[][];
            mouth?: number[];
            /** Format: double */
            strokeWidth: number;
            brows?: number[][];
        };
        /** BlobState */
        BlobState: {
            id: string;
            outline?: number[];
            /** Format: double */
            outlineWidth: number;
            isDotted: boolean;
            gradient?: number[];
            ground?: number[];
            light: components["schemas"]["BlobColours"];
            dark: components["schemas"]["BlobColours"];
            face?: components["schemas"]["BlobFace"];
        };
        /** Bootstrap */
        Bootstrap: {
            timeZone: string;
            limits: components["schemas"]["Limits"];
            consents?: string[];
        };
        /**
         * CalendarCellState
         * @enum {string}
         */
        CalendarCellState: "empty" | "future" | "value";
        /** CalendarDay */
        CalendarDay: {
            /** Format: date */
            date: string;
            swatch: components["schemas"]["CalendarSwatch"];
            name: string;
            value?: string;
        };
        /** CalendarLegendEntry */
        CalendarLegendEntry: {
            label: string;
            swatch: components["schemas"]["CalendarSwatch"];
        };
        /** CalendarSwatch */
        CalendarSwatch: {
            state: components["schemas"]["CalendarCellState"];
            /** Format: int32 */
            step?: number;
            hasNoRecord: boolean;
            blobState?: string;
        };
        /** Chart */
        Chart: {
            id: string;
            title: string;
            summary: string;
            scene: components["schemas"]["Scene"];
            table: components["schemas"]["ChartTable"];
            marks?: string[];
        };
        /** ChartTable */
        ChartTable: {
            caption: string;
            columns?: string[];
            rows?: string[][];
        };
        /** ConsentList */
        ConsentList: {
            items?: components["schemas"]["ConsentView"][];
            notes?: string[];
        };
        /** ConsentView */
        ConsentView: {
            purpose: string;
            title: string;
            granted: boolean;
            text: string;
            /** Format: int32 */
            noticeVersion?: number;
        };
        /** DayProfileInput */
        DayProfileInput: {
            wake: string;
            breakfast: string;
            lunch: string;
            dinner: string;
            bedtime: string;
            workDays?: number[];
            workStart: string;
            workEnd: string;
            quietStart: string;
            quietEnd: string;
        };
        /** DayProfileView */
        DayProfileView: {
            /** Format: int64 */
            version: number;
            wake: string;
            breakfast: string;
            lunch: string;
            dinner: string;
            bedtime: string;
            workDays?: number[];
            workStart: string;
            workEnd: string;
            quietStart: string;
            quietEnd: string;
        };
        /** DayView */
        DayView: {
            /** Format: date */
            date: string;
            dateLabel: string;
            timeZone: string;
            isToday: boolean;
            /** Format: date */
            previousDate: string;
            /** Format: date */
            nextDate: string;
            occurrences?: components["schemas"]["DoseOccurrence"][];
            /** Format: int32 */
            nowMinute?: number;
        };
        /** DoseOccurrence */
        DoseOccurrence: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            medicationId: string;
            medicationName: string;
            scheduledTime: string;
            /** Format: date-time */
            scheduledAt: string;
            state: components["schemas"]["DoseState"];
            doseText?: string;
            resolvedTime?: string;
            snoozedUntil?: string;
        };
        /**
         * DoseState
         * @enum {string}
         */
        DoseState: "due" | "missed" | "pending" | "skipped" | "snoozed" | "taken" | "taken_late" | "unknown";
        /**
         * ErrorCode
         * @enum {string}
         */
        ErrorCode: "account_required" | "client_outdated" | "client_too_old" | "consent_required" | "feature_disabled" | "idempotency_key_reuse" | "internal" | "not_found" | "payload_too_large" | "precondition_required" | "quota_exceeded" | "rate_limited" | "session_expired" | "stepup_pending" | "stepup_required" | "unauthenticated" | "upstream_unavailable" | "validation_failed" | "version_conflict";
        /** FieldError */
        FieldError: {
            field: string;
            code: string;
        };
        /** HydrationDayView */
        HydrationDayView: {
            /** Format: date */
            date: string;
            /** Format: int32 */
            totalMl: number;
            /** Format: int32 */
            entries: number;
            text: string;
            /** Format: int32 */
            goalMl?: number;
            /** Format: int32 */
            percent?: number;
            vesselLabel?: string;
        };
        /** HydrationEntries */
        HydrationEntries: {
            /** Format: date */
            from: string;
            /** Format: date */
            to: string;
            items?: components["schemas"]["HydrationEntryView"][];
        };
        /** HydrationEntryInput */
        HydrationEntryInput: {
            /** Format: int32 */
            amountMl: number;
        };
        /** HydrationEntryView */
        HydrationEntryView: {
            /** Format: uuid */
            id: string;
            /** Format: date */
            date: string;
            time: string;
            /** Format: date-time */
            occurredAt: string;
            /** Format: int32 */
            amountMl: number;
            text: string;
        };
        /** HydrationLimits */
        HydrationLimits: {
            /** Format: int32 */
            entryMinMl: number;
            /** Format: int32 */
            entryMaxMl: number;
            /** Format: int32 */
            goalMinMl: number;
            /** Format: int32 */
            goalMaxMl: number;
            /** Format: int32 */
            presetsMax: number;
            /** Format: int32 */
            entriesPerDayMax: number;
        };
        /** HydrationLogged */
        HydrationLogged: {
            entry: components["schemas"]["HydrationEntryView"];
            day: components["schemas"]["HydrationDayView"];
            goalReachedNow: boolean;
        };
        /** HydrationSettings */
        HydrationSettings: {
            presetsMl?: number[];
            limits: components["schemas"]["HydrationLimits"];
            /** Format: int64 */
            version: number;
            /** Format: int32 */
            goalMl?: number;
        };
        /** HydrationSettingsInput */
        HydrationSettingsInput: {
            presetsMl?: number[];
            /** Format: int32 */
            goalMl?: number;
        };
        /** HydrationSummary */
        HydrationSummary: {
            /** Format: date */
            from: string;
            /** Format: date */
            to: string;
            today: components["schemas"]["HydrationDayView"];
            presetsMl?: number[];
            chart?: components["schemas"]["Chart"];
            noDataText?: string;
        };
        /**
         * ImageThemeChoice
         * @enum {string}
         */
        ImageThemeChoice: "auto" | "dark" | "light";
        /** InsightBlock */
        InsightBlock: {
            id: components["schemas"]["InsightDomain"];
            title: string;
            lines?: string[];
            noDataText?: string;
            vessel?: components["schemas"]["InsightVessel"];
            charts?: components["schemas"]["Chart"][];
        };
        /** InsightCalendar */
        InsightCalendar: {
            domain: components["schemas"]["InsightDomain"];
            month: string;
            monthLabel: string;
            previousMonth: string;
            /** Format: int32 */
            steps: number;
            /** Format: int32 */
            weekStart: number;
            days?: components["schemas"]["CalendarDay"][];
            legend?: components["schemas"]["CalendarLegendEntry"][];
            summary: string;
            domains?: components["schemas"]["InsightDomain"][];
            nextMonth?: string;
        };
        /**
         * InsightDomain
         * @enum {string}
         */
        InsightDomain: "hydration" | "medication" | "mood" | "sleep";
        /** InsightItem */
        InsightItem: {
            id: string;
            text: string;
            howWorkedOut?: string[];
        };
        /** InsightList */
        InsightList: {
            /** Format: int32 */
            windowDays: number;
            windowLabel: string;
            crossDomain: boolean;
            items?: components["schemas"]["InsightItem"][];
            close?: string[];
            notice?: string;
        };
        /** InsightMedicationRow */
        InsightMedicationRow: {
            id: string;
            name: string;
            archived: boolean;
            line: string;
            /** Format: int32 */
            taken: number;
            /** Format: int32 */
            resolved: number;
            /** Format: int32 */
            percent?: number;
            /** Format: int32 */
            asNeededLogs: number;
        };
        /** InsightMonth */
        InsightMonth: {
            month: string;
            monthLabel: string;
            previousMonth: string;
            nextMonth?: string;
            timeZone: string;
            reportable: boolean;
            /** Format: int32 */
            daysWithRecords: number;
            summary: string;
            domains?: components["schemas"]["InsightMonthDomain"][];
            medications?: components["schemas"]["InsightMedicationRow"][];
            notice?: string;
        };
        /** InsightMonthDomain */
        InsightMonthDomain: {
            id: components["schemas"]["InsightDomain"];
            title: string;
            /** Format: int32 */
            recordedDays: number;
            meetsGate: boolean;
            lines?: string[];
            gateText?: string;
        };
        /** InsightVessel */
        InsightVessel: {
            /** Format: int32 */
            valueMl: number;
            /** Format: int32 */
            goalMl: number;
            label: string;
        };
        /** InsightsOverview */
        InsightsOverview: {
            /** Format: int32 */
            rangeDays: number;
            rangeLabel: string;
            timeZone: string;
            ranges?: number[];
            blocks?: components["schemas"]["InsightBlock"][];
            notice?: string;
        };
        /** LanguageChoice */
        LanguageChoice: {
            code: string;
            label: string;
        };
        /** Limits */
        Limits: {
            /** Format: int32 */
            medicationNameMax: number;
            /** Format: int32 */
            instructionsMax: number;
            /** Format: int32 */
            doseUnitMax: number;
            /** Format: int32 */
            scheduleTimesMax: number;
        };
        /** MealEntryInput */
        MealEntryInput: {
            mealType: components["schemas"]["MealKind"];
            /** Format: date-time */
            occurredAt?: string;
            description?: string;
            /** Format: uuid */
            supersedes?: string;
        };
        /**
         * MealKind
         * @enum {string}
         */
        MealKind: "breakfast" | "dinner" | "lunch" | "snack";
        /** MealRecent */
        MealRecent: {
            mealType: components["schemas"]["MealKind"];
            descriptions?: string[];
        };
        /** MealRecordedView */
        MealRecordedView: {
            meal: components["schemas"]["MealView"];
            day: components["schemas"]["NutritionDay"];
        };
        /** MealView */
        MealView: {
            /** Format: uuid */
            id: string;
            /** Format: date */
            date: string;
            time: string;
            /** Format: date-time */
            occurredAt: string;
            mealType: components["schemas"]["MealKind"];
            label: string;
            text: string;
            description?: string;
        };
        /** Meals */
        Meals: {
            /** Format: date */
            from: string;
            /** Format: date */
            to: string;
            items?: components["schemas"]["MealView"][];
        };
        /** Medication */
        Medication: {
            /** Format: uuid */
            id: string;
            name: string;
            status: components["schemas"]["MedicationState"];
            /** Format: int64 */
            version: number;
            /** Format: date-time */
            createdAt: string;
            schedules?: components["schemas"]["Schedule"][];
            doseAmount?: string;
            doseUnit?: string;
            instructions?: string;
        };
        /**
         * MedicationChange
         * @enum {string}
         */
        MedicationChange: "archive" | "pause" | "resume";
        /** MedicationInput */
        MedicationInput: {
            name: string;
            doseAmount?: string;
            doseUnit?: string;
            instructions?: string;
        };
        /** MedicationList */
        MedicationList: {
            items?: components["schemas"]["MedicationSummary"][];
        };
        /** MedicationOptions */
        MedicationOptions: {
            doseUnits?: string[];
            limits: components["schemas"]["Limits"];
        };
        /** MedicationPresetView */
        MedicationPresetView: {
            id: string;
            name: string;
            available: boolean;
            preview?: string[];
            draft?: components["schemas"]["ScheduleDraft"];
        };
        /**
         * MedicationState
         * @enum {string}
         */
        MedicationState: "active" | "archived" | "paused";
        /** MedicationStateChange */
        MedicationStateChange: {
            change: components["schemas"]["MedicationChange"];
        };
        /** MedicationSummary */
        MedicationSummary: {
            /** Format: uuid */
            id: string;
            name: string;
            status: components["schemas"]["MedicationState"];
            /** Format: int64 */
            version: number;
            scheduleSummary: string;
            doseText?: string;
        };
        /** MoodBlobStates */
        MoodBlobStates: {
            revision: string;
            states?: components["schemas"]["BlobState"][];
        };
        /** MoodCheckinInput */
        MoodCheckinInput: {
            /** Format: int32 */
            valence: number;
            /** Format: int32 */
            energy?: number;
        };
        /** MoodCheckinView */
        MoodCheckinView: {
            /** Format: uuid */
            id: string;
            /** Format: date */
            date: string;
            time: string;
            /** Format: date-time */
            occurredAt: string;
            /** Format: int32 */
            valence: number;
            label: string;
            blobState: string;
            /** Format: int32 */
            energy?: number;
        };
        /** MoodCheckins */
        MoodCheckins: {
            /** Format: date */
            from: string;
            /** Format: date */
            to: string;
            items?: components["schemas"]["MoodCheckinView"][];
        };
        /** MoodDayView */
        MoodDayView: {
            /** Format: date */
            date: string;
            /** Format: int32 */
            checkIns: number;
            text: string;
            blobState: string;
            latest?: components["schemas"]["MoodCheckinView"];
        };
        /** MoodRecordedView */
        MoodRecordedView: {
            checkin: components["schemas"]["MoodCheckinView"];
            day: components["schemas"]["MoodDayView"];
        };
        /** MoodScale */
        MoodScale: {
            valence?: components["schemas"]["MoodStop"][];
            energy?: components["schemas"]["MoodStop"][];
            blobRevision: string;
            statement: string;
        };
        /** MoodStop */
        MoodStop: {
            /** Format: int32 */
            step: number;
            label: string;
        };
        /** MoodSummary */
        MoodSummary: {
            /** Format: date */
            from: string;
            /** Format: date */
            to: string;
            today: components["schemas"]["MoodDayView"];
            days?: components["schemas"]["MoodSummaryDay"][];
        };
        /** MoodSummaryDay */
        MoodSummaryDay: {
            /** Format: date */
            date: string;
            label: string;
            name: string;
            blobState: string;
            /** Format: int32 */
            checkIns: number;
        };
        /** NotificationSettingsInput */
        NotificationSettingsInput: {
            discreet: boolean;
            quietHours?: components["schemas"]["QuietHoursView"];
        };
        /** NotificationSettingsView */
        NotificationSettingsView: {
            discreet: boolean;
            notes?: string[];
            /** Format: int64 */
            version: number;
            quietHours?: components["schemas"]["QuietHoursView"];
        };
        /** NutritionDay */
        NutritionDay: {
            /** Format: date */
            date: string;
            label: string;
            text: string;
            /** Format: int32 */
            meals: number;
            /** Format: int32 */
            snacks: number;
            /** Format: int32 */
            planned: number;
            /** Format: int32 */
            plannedLogged: number;
            logged?: components["schemas"]["MealKind"][];
            notLogged?: components["schemas"]["MealKind"][];
        };
        /** NutritionMealDays */
        NutritionMealDays: {
            mealType: components["schemas"]["MealKind"];
            label: string;
            /** Format: int32 */
            days: number;
        };
        /** NutritionSettings */
        NutritionSettings: {
            /** Format: int64 */
            version: number;
            planned?: components["schemas"]["MealKind"][];
        };
        /** NutritionSettingsInput */
        NutritionSettingsInput: {
            planned?: components["schemas"]["MealKind"][];
        };
        /** NutritionSummary */
        NutritionSummary: {
            /** Format: date */
            from: string;
            /** Format: date */
            to: string;
            today: components["schemas"]["NutritionDay"];
            days?: components["schemas"]["NutritionDay"][];
            perMeal?: components["schemas"]["NutritionMealDays"][];
            text: string;
        };
        /** PreviewDay */
        PreviewDay: {
            /** Format: date */
            date: string;
            label: string;
            occurrences?: components["schemas"]["PreviewOccurrence"][];
        };
        /** PreviewOccurrence */
        PreviewOccurrence: {
            time: string;
            /** Format: date-time */
            at: string;
            note?: string;
        };
        /** Problem */
        Problem: {
            type: string;
            title: string;
            /** Format: int32 */
            status: number;
            code: components["schemas"]["ErrorCode"];
            requestId: string;
            errors?: components["schemas"]["FieldError"][];
            medication?: components["schemas"]["Medication"];
            schedule?: components["schemas"]["Schedule"];
            purpose?: string;
        };
        /** PromptPresetView */
        PromptPresetView: {
            id: string;
            kind: string;
            name: string;
            available: boolean;
            preview?: string[];
        };
        /** QuietHoursView */
        QuietHoursView: {
            start: string;
            end: string;
        };
        /** ReminderKindView */
        ReminderKindView: {
            kind: string;
            name: string;
            available: boolean;
            presetId?: string;
            presetName?: string;
            /** Format: date-time */
            nextAt?: string;
        };
        /** ReminderPauseInput */
        ReminderPauseInput: {
            /** Format: int32 */
            hours: number;
        };
        /** ReminderPlanInput */
        ReminderPlanInput: {
            presetId: string;
            /** Format: int32 */
            everyMinutes?: number;
            from?: string;
            until?: string;
            weekdays?: number[];
        };
        /** ReminderPresets */
        ReminderPresets: {
            prompts?: components["schemas"]["PromptPresetView"][];
            medication?: components["schemas"]["MedicationPresetView"][];
        };
        /** ReminderTimeline */
        ReminderTimeline: {
            /** Format: date */
            date: string;
            entries?: components["schemas"]["ReminderTimelineEntry"][];
        };
        /** ReminderTimelineEntry */
        ReminderTimelineEntry: {
            /** Format: date-time */
            at: string;
            kind: string;
            name: string;
        };
        /** RemindersView */
        RemindersView: {
            timeZone: string;
            kinds?: components["schemas"]["ReminderKindView"][];
            /** Format: int32 */
            medicationSchedules: number;
            day: components["schemas"]["DayProfileView"];
            /** Format: date-time */
            pausedUntil?: string;
        };
        /** Scene */
        Scene: {
            /** Format: int32 */
            width: number;
            /** Format: int32 */
            height: number;
            nodes?: components["schemas"]["SceneNode"][];
        };
        /**
         * SceneAnchor
         * @enum {string}
         */
        SceneAnchor: "end" | "middle" | "start";
        /** SceneNode */
        SceneNode: {
            shape: components["schemas"]["SceneShape"];
            /** Format: double */
            x: number;
            /** Format: double */
            y: number;
            tone: components["schemas"]["SceneTone"];
            /** Format: double */
            width?: number;
            /** Format: double */
            height?: number;
            /** Format: double */
            x2?: number;
            /** Format: double */
            y2?: number;
            /** Format: double */
            radius?: number;
            text?: string;
            anchor?: components["schemas"]["SceneAnchor"];
            /** Format: double */
            opacity?: number;
            style?: components["schemas"]["SceneStyle"];
            /** Format: int32 */
            mark?: number;
        };
        /**
         * SceneShape
         * @enum {string}
         */
        SceneShape: "circle" | "hit" | "line" | "rect" | "text";
        /**
         * SceneStyle
         * @enum {string}
         */
        SceneStyle: "dashed" | "outlined";
        /**
         * SceneTone
         * @enum {string}
         */
        SceneTone: "domain-hydration" | "text" | "domain-medication" | "domain-mood" | "text-muted" | "recorded" | "separator" | "domain-sleep";
        /** Schedule */
        Schedule: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            medicationId: string;
            status: components["schemas"]["ScheduleState"];
            /** Format: int32 */
            revision: number;
            pattern: components["schemas"]["SchedulePattern"];
            times?: string[];
            weekdays?: number[];
            timeZone: string;
            /** Format: date */
            startDate: string;
            summary: string;
            /** Format: int32 */
            maxPerDay?: number;
            /** Format: int32 */
            minGapMinutes?: number;
            /** Format: date */
            endDate?: string;
        };
        /**
         * ScheduleChange
         * @enum {string}
         */
        ScheduleChange: "end" | "pause" | "resume";
        /** ScheduleDraft */
        ScheduleDraft: {
            pattern: components["schemas"]["SchedulePattern"];
            times?: string[];
            weekdays?: number[];
            /** Format: int32 */
            maxPerDay?: number;
            /** Format: int32 */
            minGapMinutes?: number;
            /** Format: date */
            startDate?: string;
            /** Format: date */
            endDate?: string;
        };
        /** ScheduleList */
        ScheduleList: {
            items?: components["schemas"]["Schedule"][];
        };
        /** ScheduleOptions */
        ScheduleOptions: {
            patterns?: components["schemas"]["SchedulePattern"][];
            /** Format: int32 */
            timesMax: number;
            /** Format: int32 */
            previewDays: number;
            timeZone: string;
            /** Format: date */
            today: string;
        };
        /**
         * SchedulePattern
         * @enum {string}
         */
        SchedulePattern: "as_needed" | "fixed_times";
        /** SchedulePreview */
        SchedulePreview: {
            summary: string;
            timeZone: string;
            days?: components["schemas"]["PreviewDay"][];
        };
        /** SchedulePreviewRequest */
        SchedulePreviewRequest: {
            draft: components["schemas"]["ScheduleDraft"];
        };
        /**
         * ScheduleState
         * @enum {string}
         */
        ScheduleState: "active" | "ended" | "paused";
        /** ScheduleStateChange */
        ScheduleStateChange: {
            change: components["schemas"]["ScheduleChange"];
        };
        /** SessionCreated */
        SessionCreated: {
            token: string;
            /** Format: date-time */
            expiresAt: string;
            /** Format: int64 */
            idleTimeoutSeconds: number;
            bootstrap: components["schemas"]["Bootstrap"];
        };
        /** SessionRequest */
        SessionRequest: {
            initData: string;
            deviceTimeZone?: string;
            build?: string;
            platform?: string;
            version?: string;
            colorScheme?: string;
        };
        /** SettingsInput */
        SettingsInput: {
            language: string;
            timeZone: string;
            imageTheme: components["schemas"]["ImageThemeChoice"];
        };
        /** SettingsView */
        SettingsView: {
            language: string;
            languages?: components["schemas"]["LanguageChoice"][];
            timeZone: string;
            imageTheme: components["schemas"]["ImageThemeChoice"];
            /** Format: int64 */
            version: number;
        };
        /** SleepCheckinInput */
        SleepCheckinInput: {
            kind: components["schemas"]["SleepCheckinKind"];
        };
        /**
         * SleepCheckinKind
         * @enum {string}
         */
        SleepCheckinKind: "awake" | "going_to_sleep";
        /** SleepEpisodeInput */
        SleepEpisodeInput: {
            kind: components["schemas"]["SleepEpisodeKind"];
            /** Format: date-time */
            onsetAt: string;
            /** Format: date-time */
            wakeAt: string;
        };
        /**
         * SleepEpisodeKind
         * @enum {string}
         */
        SleepEpisodeKind: "nap" | "night";
        /** SleepEpisodeView */
        SleepEpisodeView: {
            /** Format: uuid */
            id: string;
            kind: components["schemas"]["SleepEpisodeKind"];
            /** Format: date */
            date: string;
            /** Format: date-time */
            onsetAt: string;
            /** Format: date-time */
            wakeAt: string;
            bedtime: string;
            wakeTime: string;
            /** Format: int32 */
            minutes: number;
            duration: string;
            source: string;
            text: string;
            /** Format: int32 */
            quality?: number;
        };
        /** SleepEpisodes */
        SleepEpisodes: {
            /** Format: date */
            from: string;
            /** Format: date */
            to: string;
            items?: components["schemas"]["SleepEpisodeView"][];
        };
        /** SleepLimits */
        SleepLimits: {
            /** Format: int32 */
            goalMinMinutes: number;
            /** Format: int32 */
            goalMaxMinutes: number;
        };
        /** SleepSettings */
        SleepSettings: {
            limits: components["schemas"]["SleepLimits"];
            /** Format: int64 */
            version: number;
            /** Format: int32 */
            goalMinutes?: number;
        };
        /** SleepSettingsInput */
        SleepSettingsInput: {
            /** Format: int32 */
            goalMinutes?: number;
        };
        /** SleepStatusView */
        SleepStatusView: {
            text: string;
            openSince?: string;
            lastNight?: components["schemas"]["SleepEpisodeView"];
            /** Format: int32 */
            goalMinutes?: number;
            goalText?: string;
            needsBedtime: boolean;
        };
        /** SleepSummary */
        SleepSummary: {
            /** Format: date */
            from: string;
            /** Format: date */
            to: string;
            status: components["schemas"]["SleepStatusView"];
            lines?: string[];
            days?: components["schemas"]["SleepSummaryDay"][];
            chart?: components["schemas"]["Chart"];
            noDataText?: string;
        };
        /** SleepSummaryDay */
        SleepSummaryDay: {
            /** Format: date */
            date: string;
            label: string;
            name: string;
            /** Format: int32 */
            minutes?: number;
        };
        /** TimeZoneChoice */
        TimeZoneChoice: {
            id: string;
            offset: string;
        };
        /** TimeZoneList */
        TimeZoneList: {
            items?: components["schemas"]["TimeZoneChoice"][];
        };
        /** TimeZonePreview */
        TimeZonePreview: {
            timeZone: string;
            localTime: string;
            lines?: string[];
        };
        /** TimeZonePreviewRequest */
        TimeZonePreviewRequest: {
            timeZone: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    createSession: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SessionRequest"];
            };
        };
        responses: {
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SessionCreated"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    deleteSession: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    keepSessionAlive: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getSettings: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SettingsView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    updateSettings: {
        parameters: {
            query?: never;
            header?: {
                /** @description The ETag the edit is based on; required */
                "If-Match"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SettingsInput"];
            };
        };
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SettingsView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    listTimeZones: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TimeZoneList"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    previewTimeZone: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TimeZonePreviewRequest"];
            };
        };
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TimeZonePreview"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    listConsents: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConsentList"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    withdrawConsent: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                purpose: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getToday: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DayView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getDay: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                date: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DayView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getMedicationOptions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MedicationOptions"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    listMedications: {
        parameters: {
            query?: {
                status?: components["schemas"]["MedicationState"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MedicationList"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    createMedication: {
        parameters: {
            query?: never;
            header?: {
                /** @description A UUID per user action; required (API-16) */
                "Idempotency-Key"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MedicationInput"];
            };
        };
        responses: {
            201: {
                headers: {
                    Location: string;
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Medication"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getMedication: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Medication"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    updateMedication: {
        parameters: {
            query?: never;
            header?: {
                /** @description The ETag the edit is based on; required */
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MedicationInput"];
            };
        };
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Medication"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    changeMedicationState: {
        parameters: {
            query?: never;
            header?: {
                /** @description The ETag the edit is based on; required */
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MedicationStateChange"];
            };
        };
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Medication"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getScheduleOptions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ScheduleOptions"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    previewSchedule: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SchedulePreviewRequest"];
            };
        };
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SchedulePreview"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    listSchedules: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ScheduleList"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    createSchedule: {
        parameters: {
            query?: never;
            header?: {
                /** @description A UUID per user action; required (API-16) */
                "Idempotency-Key"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ScheduleDraft"];
            };
        };
        responses: {
            201: {
                headers: {
                    Location: string;
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Schedule"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getSchedule: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Schedule"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    reviseSchedule: {
        parameters: {
            query?: never;
            header?: {
                /** @description The ETag the edit is based on; required */
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ScheduleDraft"];
            };
        };
        responses: {
            201: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Schedule"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    changeScheduleState: {
        parameters: {
            query?: never;
            header?: {
                /** @description The ETag the edit is based on; required */
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ScheduleStateChange"];
            };
        };
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Schedule"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getNotificationSettings: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NotificationSettingsView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    updateNotificationSettings: {
        parameters: {
            query?: never;
            header?: {
                /** @description The ETag the edit is based on; required */
                "If-Match"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NotificationSettingsInput"];
            };
        };
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NotificationSettingsView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    listHydrationEntries: {
        parameters: {
            query?: {
                /** @description First local day; with `to`, at most 366 days */
                from?: string;
                /** @description Last local day */
                to?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HydrationEntries"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    createHydrationEntry: {
        parameters: {
            query?: never;
            header?: {
                /** @description A UUID per user action; required (API-16) */
                "Idempotency-Key"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["HydrationEntryInput"];
            };
        };
        responses: {
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HydrationLogged"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    deleteHydrationEntry: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HydrationDayView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getHydrationSettings: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HydrationSettings"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    updateHydrationSettings: {
        parameters: {
            query?: never;
            header?: {
                /** @description The ETag the edit is based on; required */
                "If-Match"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["HydrationSettingsInput"];
            };
        };
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HydrationSettings"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getHydrationSummary: {
        parameters: {
            query?: {
                /** @description First local day; with `to`, at most 366 days */
                from?: string;
                /** @description Last local day */
                to?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HydrationSummary"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getMoodScale: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MoodScale"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getMoodBlobStates: {
        parameters: {
            query?: {
                revision?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MoodBlobStates"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    listMoodCheckins: {
        parameters: {
            query?: {
                /** @description First local day; with `to`, at most 366 days */
                from?: string;
                /** @description Last local day */
                to?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MoodCheckins"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    createMoodCheckin: {
        parameters: {
            query?: never;
            header?: {
                /** @description A UUID per user action; required (API-16) */
                "Idempotency-Key"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MoodCheckinInput"];
            };
        };
        responses: {
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MoodRecordedView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    deleteMoodCheckin: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MoodDayView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getMoodSummary: {
        parameters: {
            query?: {
                /** @description First local day; with `to`, at most 366 days */
                from?: string;
                /** @description Last local day */
                to?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MoodSummary"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    listSleepEpisodes: {
        parameters: {
            query?: {
                /** @description First local day; with `to`, at most 366 days */
                from?: string;
                /** @description Last local day */
                to?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SleepEpisodes"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    createSleepEpisode: {
        parameters: {
            query?: never;
            header?: {
                /** @description A UUID per user action; required (API-16) */
                "Idempotency-Key"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SleepEpisodeInput"];
            };
        };
        responses: {
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SleepEpisodeView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    deleteSleepEpisode: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SleepStatusView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    createSleepCheckin: {
        parameters: {
            query?: never;
            header?: {
                /** @description A UUID per user action; required (API-16) */
                "Idempotency-Key"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SleepCheckinInput"];
            };
        };
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SleepStatusView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getSleepSettings: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SleepSettings"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    updateSleepSettings: {
        parameters: {
            query?: never;
            header?: {
                /** @description The ETag the edit is based on; required */
                "If-Match"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SleepSettingsInput"];
            };
        };
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SleepSettings"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getSleepSummary: {
        parameters: {
            query?: {
                /** @description First local day; with `to`, at most 366 days */
                from?: string;
                /** @description Last local day */
                to?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SleepSummary"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getInsightCalendar: {
        parameters: {
            query: {
                domain: components["schemas"]["InsightDomain"];
                /** @description YYYY-MM; absent is the current month */
                month?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InsightCalendar"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getInsightsOverview: {
        parameters: {
            query: {
                /** @description The number of days the statistics cover: 7 or 30 */
                range: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InsightsOverview"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getInsightMonth: {
        parameters: {
            query?: {
                /** @description YYYY-MM; absent is the current month */
                month?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InsightMonth"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    listInsightCorrelations: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InsightList"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getReminders: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RemindersView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    listReminderPresets: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReminderPresets"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    createReminderPlan: {
        parameters: {
            query?: never;
            header?: {
                /** @description A UUID per user action; required (API-16) */
                "Idempotency-Key"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReminderPlanInput"];
            };
        };
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RemindersView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    deleteReminderPlan: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                kind: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RemindersView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    createReminderPause: {
        parameters: {
            query?: never;
            header?: {
                /** @description A UUID per user action; required (API-16) */
                "Idempotency-Key"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReminderPauseInput"];
            };
        };
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RemindersView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getDayProfile: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DayProfileView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    updateDayProfile: {
        parameters: {
            query?: never;
            header?: {
                /** @description The ETag the edit is based on; required */
                "If-Match"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DayProfileInput"];
            };
        };
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DayProfileView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getReminderTimeline: {
        parameters: {
            query?: {
                /** @description A local day; absent is tomorrow */
                date?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReminderTimeline"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    listMeals: {
        parameters: {
            query?: {
                /** @description First local day; with `to`, at most 366 days */
                from?: string;
                /** @description Last local day */
                to?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Meals"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    createMeal: {
        parameters: {
            query?: never;
            header?: {
                /** @description A UUID per user action; required (API-16) */
                "Idempotency-Key"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MealEntryInput"];
            };
        };
        responses: {
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MealRecordedView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    listRecentMeals: {
        parameters: {
            query: {
                mealType: components["schemas"]["MealKind"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MealRecent"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getMeal: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MealView"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    deleteMeal: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NutritionDay"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getNutritionSummary: {
        parameters: {
            query?: {
                /** @description First local day; with `to`, at most 366 days */
                from?: string;
                /** @description Last local day */
                to?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NutritionSummary"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getNutritionSettings: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NutritionSettings"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    updateNutritionSettings: {
        parameters: {
            query?: never;
            header?: {
                /** @description The ETag the edit is based on; required */
                "If-Match"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NutritionSettingsInput"];
            };
        };
        responses: {
            200: {
                headers: {
                    /** @description "v<version>" of the representation (API-17) */
                    ETag: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NutritionSettings"];
                };
            };
            default: {
                headers: {
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
}
