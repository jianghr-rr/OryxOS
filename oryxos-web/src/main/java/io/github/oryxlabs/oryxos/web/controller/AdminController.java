package io.github.oryxlabs.oryxos.web.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Static console lives in the web module and uses only the existing same-origin API. */
@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminController {
  @GetMapping("/admin")
  public String canonical() {
    return "redirect:/admin/";
  }

  @GetMapping("/admin/")
  public String index() {
    return "forward:/admin/index.html";
  }
}
