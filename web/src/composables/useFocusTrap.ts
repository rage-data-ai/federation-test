import { nextTick, onUnmounted, watch, type Ref } from 'vue'

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * Keep Tab focus inside an open dialog and close on Escape.
 */
export function useFocusTrap(
  active: Ref<boolean>,
  root: Ref<HTMLElement | null | undefined>,
  onEscape?: () => void
) {
  let previous: HTMLElement | null = null

  function onKeydown(event: KeyboardEvent) {
    if (!active.value || !root.value) {
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      onEscape?.()
      return
    }
    if (event.key !== 'Tab') {
      return
    }
    const nodes = Array.from(root.value.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
      (el) => !el.hasAttribute('disabled') && el.offsetParent !== null
    )
    if (!nodes.length) {
      return
    }
    const first = nodes[0]
    const last = nodes[nodes.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  watch(
    active,
    async (open) => {
      if (open) {
        previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
        await nextTick()
        const first = root.value?.querySelector<HTMLElement>(FOCUSABLE)
        first?.focus()
        document.addEventListener('keydown', onKeydown, true)
      } else {
        document.removeEventListener('keydown', onKeydown, true)
        previous?.focus()
        previous = null
      }
    },
    { flush: 'post' }
  )

  onUnmounted(() => document.removeEventListener('keydown', onKeydown, true))
}
