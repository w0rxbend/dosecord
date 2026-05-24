"""Compatibility imports for shared contracts.

New code should import from `shared.contracts` directly. This module keeps older
bot imports stable while contracts move into the monorepo shared package.
"""

from shared.contracts import Actor as EventActor
from shared.contracts import MessageEnvelope as WellbeingEvent
from shared.contracts import Platform
from shared.contracts.wellbeing import (
    HabitCheckinRecordRequestedData as HabitEvent,
)
from shared.contracts.wellbeing import (
    MedicationIntakeMarkTakenRequestedData as MedicineEvent,
)
from shared.contracts.wellbeing import MoodCheckinRecordRequestedData as MoodEvent

__all__ = [
    "EventActor",
    "HabitEvent",
    "MedicineEvent",
    "MoodEvent",
    "Platform",
    "WellbeingEvent",
]
