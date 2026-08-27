# Visual specification

The reference image is a photographed display, so its white balance is not treated as an exact source color. The implementation uses a corrected warm-neutral palette.

| Token | Value | Use |
| --- | --- | --- |
| Cream | `#F6F7E2` | Home, status and navigation background |
| Ink | `#151B17` | Tiles, text, controls and inactive dots |
| Orange | `#FF7138` | Notification pad and today's calendar dot |
| Panel | `#F1F2DC` | Low-contrast tile containers |
| Muted | `#E4E6D2` | Calendar cells outside the current month |
| Yellow | `#FFDA1A` | Secondary-app and knob markers |

Typography uses Android's `sans-serif` family to avoid shipping or copying a proprietary typeface. Weight, scale, opacity and motion create the visual hierarchy.

## Motion

- Press target scale: `0.82`
- Release overshoot limit: `1.16`
- Spring strength: `52`
- Damping curve: `0.085^dt`
- Media marquee: `36dp/s`
- Vertical-wave phase: `2.1 cycles/s` while playing; 8/10 centered columns for cover/inner profiles, with maximum height equal to the pause control
- Calendar reveal: `14ms` stagger per cell

## Responsive profiles

- Galaxy Z Fold8 cover target: `1248 × 1972`, 10:16 portrait ratio, two tile columns and four rows.
- Galaxy Z Fold8 inner target: `1828 × 2448`, approximately 3:4 portrait ratio, four tile columns and two rows.
- Runtime profile selection combines a `560dp` width boundary with a `1.48` portrait-ratio boundary.
- The current activity handles `screenSize`, `smallestScreenSize`, orientation and density changes without restarting, allowing immediate fold/unfold transitions.
