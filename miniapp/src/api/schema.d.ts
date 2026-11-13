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
        InsightDomain: "hydration" | "medication" | "mood";
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
        /** QuietHoursView */
        QuietHoursView: {
            start: string;
            end: string;
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
        SceneTone: "domain-hydration" | "text" | "domain-medication" | "domain-mood" | "text-muted" | "recorded" | "separator";
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
}
