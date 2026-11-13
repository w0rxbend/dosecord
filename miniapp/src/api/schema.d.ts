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
        /** Bootstrap */
        Bootstrap: {
            timeZone: string;
            limits: components["schemas"]["Limits"];
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
        /** InsightBlock */
        InsightBlock: {
            id: components["schemas"]["InsightDomain"];
            title: string;
            lines?: string[];
            noDataText?: string;
            vessel?: components["schemas"]["InsightVessel"];
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
        };
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
